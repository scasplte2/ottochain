package xyz.kd5ujc.shared_data.fiber.triggers

import cats.data.EitherT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, ScriptInvocation}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.{FiberEvaluator, ScriptProcessor}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Handles individual trigger processing in G[_] with gas tracked via StateT
 * and config read from FiberContext via Ask.
 *
 * Dispatches to appropriate handler based on fiber type:
 * - StateMachineFiberRecord: delegates to FiberEvaluator for guard/effect evaluation
 * - ScriptFiberRecord: evaluates script directly with gas metering
 */
trait TriggerHandler[G[_]] {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult]
}

object TriggerHandler {

  def make[F[_]: Async: SecurityProvider, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): TriggerHandler[G] =
    (trigger: FiberTrigger, fiber: Records.FiberRecord, state: CalculatedState) =>
      fiber match {
        case _: Records.StateMachineFiberRecord =>
          new StateMachineTriggerHandler[F, G](state)
            .handle(trigger, fiber, state)

        case _: Records.ScriptFiberRecord =>
          new ScriptTriggerHandler[F, G]()
            .handle(trigger, fiber, state)
      }
}

class StateMachineTriggerHandler[F[_]: Async: SecurityProvider, G[_]: Monad](
  calculatedState: CalculatedState
)(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case sm: Records.StateMachineFiberRecord =>
        handleStateMachine(trigger, sm, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason
            .FiberInputMismatch(other.fiberId, FiberKind.Script, InputKind.Transition)
        ): TriggerHandlerResult).pure[G]
    }

  /**
   * The CASCADE (fiber→fiber) transition path — driven by a `_triggers` directive from another fiber, NOT by a
   * wallet-signed `TransitionStateMachine`.
   *
   * ══ AUTHORIZATION ASYMMETRY (audit 2026-07-07, finding M2 — READ BEFORE CHANGING) ══
   * A DIRECT (wallet-origin) transition is owner/participant-gated at block acceptance
   * (`FiberValidator.L0` → `FiberRules.updateSignedByOwnerOrParticipant`). This cascade path passes
   * `proofs = List.empty` and does NOT run that owner gate. On the cascade the ONLY gates are:
   *   1. the target fiber's guard expression, and
   *   2. the target's `FiberPolicy.acceptedCallers` allowlist (checked in `FiberEvaluator.policyShortCircuit`),
   *      which DEFAULTS TO OPEN when unset.
   * Consequently ANY account can drive ANY fiber's transition through a one-hop `_triggers` unless the target
   * fiber constrains it. An app author who assumes "only owners can advance my machine" (true on the direct
   * path) and omits `$caller`/`$proofs` checks in the guard is fully drivable by an arbitrary cascade caller.
   *
   * App-author guidance: on any transition reachable via `_triggers`, treat the GUARD + `acceptedCallers` as
   * the sole authorization boundary — inspect the engine-stamped, non-spoofable `$caller` (surfaced below) and,
   * where relevant, `$proofs`. Do NOT rely on the direct-path owner gate for cascade-reachable transitions.
   *
   * The default is intentionally left OPEN here (flipping it to closed would break every existing
   * cross-fiber composition, and the default is a signed/omit-safe field per CLAUDE.md rule #1); the fix is to
   * make the asymmetry explicit and give authors `$caller`/`acceptedCallers` to opt into a tighter posture.
   */
  private def handleStateMachine(
    trigger: FiberTrigger,
    sm:      Records.StateMachineFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    for {
      ordinal <- ExecutionOps.askOrdinal[G]
      // Fix (2): surface the engine-stamped cross-fiber caller into the guard context as $caller. This closes
      // the cascaded gap — `trigger.sourceFiberId` previously reached only the receipt (:99), never the guard.
      // It is non-spoofable: the engine stamps `sourceFiberId` at extraction, not the triggering fiber's logic.
      outcome <- FiberEvaluator
        .make[F, G](calculatedState)
        .evaluate(sm, trigger.input, List.empty, caller = trigger.sourceFiberId)
      result <- outcome match {
        // Cascaded (triggered) transitions do not spawn or mutate dynamic dependencies — those directives
        // (`spawns`, `dependencyMutations`) are honoured only on the PRIMARY transition (see FiberEngine),
        // so both are ignored here, as `spawns` already is. `_consumeNullifier` IS honoured on the cascade
        // (like Transfer/Emit): the domain is the triggered fiber's OWN id, so a cascade can only ever
        // consume into its own namespace.
        case FiberResult.Success(
              newStateData,
              newStateId,
              triggers,
              _,
              _,
              emittedEvents,
              assetTransfers,
              _,
              nullifierConsumptions
            ) =>
          val receipt = EventReceipt.success(
            sm = sm,
            eventName = trigger.input.key,
            ordinal = ordinal,
            gasUsed = 0L,
            newStateId = newStateId,
            triggers = triggers,
            sourceFiberId = trigger.sourceFiberId,
            emittedEvents = emittedEvents
          )

          val updatedFiber = sm.copy(
            currentState = newStateId.getOrElse(sm.currentState),
            stateData = newStateData,
            sequenceNumber = sm.sequenceNumber.next,
            latestUpdateOrdinal = ordinal,
            lastReceipt = Some(receipt)
          )

          val updatedState = state.updateFiber(updatedFiber)

          ExecutionOps
            .appendLog[G](receipt)
            .as(
              TriggerHandlerResult.Success(
                updatedState = updatedState,
                cascadeTriggers = triggers,
                assetTransfers = assetTransfers,
                nullifierConsumptions = nullifierConsumptions
              ): TriggerHandlerResult
            )

        case FiberResult.GuardFailed(attemptedCount) =>
          (TriggerHandlerResult.Failed(
            FailureReason.NoGuardMatched(sm.currentState, trigger.input.key, attemptedCount)
          ): TriggerHandlerResult).pure[G]

        case FiberResult.Failed(reason) =>
          (TriggerHandlerResult.Failed(reason): TriggerHandlerResult).pure[G]
      }
    } yield result
}

class ScriptTriggerHandler[F[_]: Async, G[_]: Monad]()(implicit
  S:    Stateful[G, ExecutionState],
  A:    Ask[G, FiberContext],
  lift: F ~> G
) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case script: Records.ScriptFiberRecord =>
        handleScript(trigger, script, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason.FiberInputMismatch(other.fiberId, FiberKind.StateMachine, InputKind.MethodCall)
        ): TriggerHandlerResult).pure[G]
    }

  private def handleScript(
    trigger: FiberTrigger,
    script:  Records.ScriptFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] = {
    type ScriptET[A] = EitherT[G, TriggerHandlerResult, A]

    val computation: ScriptET[TriggerHandlerResult] = for {
      // Fiber-origin `_scriptCall` caller resolution (audit 2026-07-07, finding H1 — amplifier hardening / L4).
      // A `Set[Address]` has arbitrary iteration order, so `owners.headOption` picked a non-deterministic owner
      // as the committed `invokedBy` / access principal. Sort by the canonical address string and take the min
      // so the chosen caller is well-defined and identical across all validators. The forgery vector this
      // amplified is closed at its source by the spawn-owner subset floor (SpawnValidator.subsetOfParentFloor):
      // a child can no longer be owned by an address its parent does not own, so this owner is a genuine
      // parent-lineage owner, not an attacker-planted victim. FOLLOW-UP: representing a fiber caller as a
      // distinct fiber-principal (rather than impersonating one of its owner wallets) is out of scope here.
      callerAddress <- EitherT.fromOption[G](
        trigger.sourceFiberId.flatMap { fiberId =>
          state.getFiber(fiberId).flatMap(_.owners.toList.sortBy(_.value.value).headOption)
        },
        TriggerHandlerResult.Failed(
          FailureReason.CallerResolutionFailed(script.fiberId, trigger.sourceFiberId)
        ): TriggerHandlerResult
      )

      _ <- EitherT[G, TriggerHandlerResult, Unit](
        ScriptProcessor.validateAccess(script.accessControl, callerAddress, script.fiberId, state).liftTo[G].map {
          case Right(_)     => Right(())
          case Left(reason) => Left(TriggerHandlerResult.Failed(reason))
        }
      )

      inputData = MapValue(
        Map(
          ReservedKeys.METHOD -> StrValue(trigger.input.key),
          ReservedKeys.ARGS   -> trigger.input.content,
          ReservedKeys.STATE  -> script.stateData.getOrElse(NullValue)
        )
      )

      remainingGas <- EitherT.liftF[G, TriggerHandlerResult, Long](
        ExecutionOps.remainingGas[G]
      )

      gasConfig <- EitherT.liftF[G, TriggerHandlerResult, GasConfig](
        ExecutionOps.askGasConfig[G]
      )

      scriptResult <- EitherT[G, TriggerHandlerResult, EvaluationResult[JsonLogicValue]](
        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(script.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
          .liftTo[G]
          .flatMap {
            case Right(result) =>
              ExecutionOps.chargeGas[G](result.gasUsed.amount).as(result.asRight[TriggerHandlerResult])

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Script).map { reason =>
                (TriggerHandlerResult.Failed(reason): TriggerHandlerResult)
                  .asLeft[EvaluationResult[JsonLogicValue]]
              }
          }
      )

      scriptGasUsed = scriptResult.gasUsed.amount
      evaluationResult = scriptResult.value

      stateAndResult <- EitherT.liftF[G, TriggerHandlerResult, (Option[JsonLogicValue], JsonLogicValue)](
        ScriptProcessor.extractStateAndResult(evaluationResult).liftTo[G]
      )
      (newStateData, returnValue) = stateAndResult

      _ <- {
        val checkResult: Either[TriggerHandlerResult, Unit] = returnValue match {
          case BoolValue(false) =>
            Left(
              TriggerHandlerResult.Failed(
                FailureReason.ScriptInvocationFailed(script.fiberId, trigger.input.key, Some("returned false"))
              )
            )
          case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
            val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
            Left(
              TriggerHandlerResult.Failed(
                FailureReason
                  .ScriptInvocationFailed(script.fiberId, trigger.input.key, Some(s"validation failed: $errorMsg"))
              )
            )
          case _ => Right(())
        }
        EitherT.fromEither[G](checkResult)
      }

      newHash <- EitherT.liftF[G, TriggerHandlerResult, Option[io.constellationnetwork.security.hash.Hash]](
        newStateData.traverse(_.computeDigest).liftTo[G]
      )

      ordinal <- EitherT.liftF[G, TriggerHandlerResult, io.constellationnetwork.schema.SnapshotOrdinal](
        ExecutionOps.askOrdinal[G]
      )

      invocation = ScriptInvocation(
        fiberId = script.fiberId,
        method = trigger.input.key,
        args = trigger.input.content,
        result = returnValue,
        gasUsed = scriptGasUsed,
        invokedAt = ordinal,
        invokedBy = callerAddress
      )

      _ <- EitherT.liftF[G, TriggerHandlerResult, Unit](
        ExecutionOps.appendLog[G](invocation)
      )

      updatedScript = script.copy(
        stateData = newStateData,
        stateDataHash = newHash,
        latestUpdateOrdinal = ordinal,
        sequenceNumber = script.sequenceNumber.next,
        lastInvocation = Some(invocation)
      )

      updatedState = state.updateFiber(updatedScript)

    } yield TriggerHandlerResult.Success(
      updatedState = updatedState,
      cascadeTriggers = List.empty
    ): TriggerHandlerResult

    computation.merge
  }
}
