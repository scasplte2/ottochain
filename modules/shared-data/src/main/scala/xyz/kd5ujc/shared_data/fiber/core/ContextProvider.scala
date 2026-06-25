package xyz.kd5ujc.shared_data.fiber.core

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber.FiberLogEntry.ScriptInvocation
import xyz.kd5ujc.schema.fiber.{FiberInput, ReservedKeys}
import xyz.kd5ujc.schema.{CalculatedState, Records}

/**
 * Builds evaluation context for JsonLogic expressions.
 *
 * For state machines, context includes:
 * - state: current state data
 * - event: event payload
 * - eventType: event type string
 * - machineId: fiber UUID
 * - currentStateId: current state ID
 * - sequenceNumber: event sequence
 * - proofs: signer addresses
 * - parent: parent fiber data (if any)
 * - children: child fiber data
 * - machines: dependent machine states
 * - scripts: dependent script states
 *
 * For scripts, context includes:
 * - _method: method name
 * - _args: method arguments
 * - _state: current script state
 */
trait ContextProvider[F[_]] {

  /**
   * Build full evaluation context for guard/effect expressions.
   * Includes proofs, dependencies, parent/child relationships.
   */
  def buildContext(
    fiber:        Records.FiberRecord,
    input:        FiberInput,
    proofs:       List[SignatureProof],
    dependencies: Set[UUID]
  ): F[JsonLogicValue]

  /**
   * Build simplified context for trigger/spawn expressions.
   * Includes fiber state, event, and parent/child relationships.
   * Does not include proofs or external dependencies.
   */
  def buildTriggerContext(
    fiber: Records.StateMachineFiberRecord,
    input: FiberInput
  ): F[JsonLogicValue]
}

object ContextProvider {

  /**
   * Create a ContextProvider with access to CalculatedState for dependency resolution.
   *
   * @param calculatedState Current calculated state for dependency lookups
   * @param ordinal Current snapshot ordinal, exposed as $ordinal in context
   * @param lastSnapshotHash Parent snapshot hash, exposed as $lastSnapshotHash for randomness
   * @param epochProgress Current epoch progress, exposed as $epochProgress
   * @param caller Engine-stamped cross-fiber caller (engine-default-fixes Fix 2), exposed as $caller. `Some(id)`
   *               for a cascaded fiber→fiber (or self) trigger; `None` for a primary/external (wallet) trigger.
   *               Non-spoofable: the engine writes `sourceFiberId` at extraction, a fiber cannot forge it.
   */
  def make[F[_]: Async: SecurityProvider](
    calculatedState:  CalculatedState,
    ordinal:          SnapshotOrdinal,
    lastSnapshotHash: Hash,
    epochProgress:    EpochProgress,
    caller:           Option[UUID]
  ): ContextProvider[F] =
    new ContextProvider[F] {

      /**
       * Holder-keyed index of fiber-held assets (asset-model.md §10), built ONCE per provider construction —
       * NOT an O(all-assets) filter per evaluation (R20 perf cliff). Maps each holding fiber id to its held
       * `AssetRecord`s; consulted by [[heldAssetsContext]] when a fiber's guards/effects are evaluated.
       */
      private val heldAssetsByFiber: Map[UUID, List[AssetRecord]] =
        calculatedState.assets.values.toList.foldLeft(Map.empty[UUID, List[AssetRecord]]) { (acc, asset) =>
          asset.holder match {
            case AssetHolder.Fiber(fid) => acc.updated(fid, asset :: acc.getOrElse(fid, List.empty))
            case AssetHolder.Wallet(_)  => acc
          }
        }

      /**
       * The `heldAssets` projection injected into a fiber's evaluation context: a map `assetId → {behavior,
       * amount, expiresAt}` over the assets this fiber holds, so guards can reason about held state, e.g.
       * `{ ">": [{ "var": "heldAssets.<id>.expiresAt" }, { "var": "$ordinal" }] }`. Deterministic and bounded
       * (one entry per held asset). An empty map for a fiber that holds nothing.
       */
      private def heldAssetsContext(fiberId: UUID): MapValue =
        MapValue(
          heldAssetsByFiber
            .getOrElse(fiberId, List.empty)
            .map { asset =>
              asset.assetId.toString -> (MapValue(
                Map(
                  ReservedKeys.BEHAVIOR -> IntValue(BigInt(asset.behavior.bits)),
                  ReservedKeys.AMOUNT   -> IntValue(BigInt(asset.amount)),
                  ReservedKeys.EXPIRES_AT -> asset.expiresAt.fold(NullValue: JsonLogicValue)(o =>
                    IntValue(o.value.value)
                  )
                )
              ): JsonLogicValue)
            }
            .toMap
        )

      def buildContext(
        fiber:        Records.FiberRecord,
        input:        FiberInput,
        proofs:       List[SignatureProof],
        dependencies: Set[UUID]
      ): F[JsonLogicValue] = fiber match {
        case sm: Records.StateMachineFiberRecord =>
          input match {
            case _: FiberInput.Transition =>
              buildStateMachineContext(sm, input.key, input.content, proofs, dependencies)
            case _: FiberInput.MethodCall =>
              Async[F].raiseError(new RuntimeException("Cannot use MethodCall input with StateMachineFiberRecord"))
          }

        case script: Records.ScriptFiberRecord =>
          input match {
            case _: FiberInput.MethodCall =>
              buildScriptContext(script, input.key, input.content)
            case _: FiberInput.Transition =>
              Async[F].raiseError(new RuntimeException("Cannot use Transition input with ScriptFiberRecord"))
          }
      }

      // === State Machine Context ===

      private def buildStateMachineContext(
        fiber:        Records.StateMachineFiberRecord,
        eventName:    String,
        payload:      JsonLogicValue,
        proofs:       List[SignatureProof],
        dependencies: Set[UUID]
      ): F[JsonLogicValue] =
        for {
          proofsData   <- buildProofsContext(proofs)
          machinesData <- buildMachinesContext(dependencies)
          parentData   <- buildParentContext(fiber)
          childrenData <- buildChildrenContext(fiber)
          scriptsData  <- buildScriptsContext(dependencies)
        } yield MapValue(
          Map(
            ReservedKeys.STATE              -> fiber.stateData,
            ReservedKeys.EVENT              -> payload,
            ReservedKeys.EVENT_NAME         -> StrValue(eventName),
            ReservedKeys.MACHINE_ID         -> StrValue(fiber.fiberId.toString),
            ReservedKeys.CURRENT_STATE_ID   -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER    -> IntValue(fiber.sequenceNumber.value.value),
            ReservedKeys.ORDINAL            -> IntValue(ordinal.value.value),
            ReservedKeys.LAST_SNAPSHOT_HASH -> StrValue(lastSnapshotHash.value),
            ReservedKeys.EPOCH_PROGRESS     -> IntValue(epochProgress.value.value),
            // Fix (2): the engine-stamped cross-fiber caller. StrValue(id) for a cascaded fiber→fiber (or self)
            // trigger; NullValue for primary/external (wallet) triggers. A self-trigger yields $caller ==
            // $machineId. This binds ONLY the fiber emitter (non-spoofable); external-wallet auth stays `proofs`.
            ReservedKeys.CALLER      -> caller.fold[JsonLogicValue](NullValue)(id => StrValue(id.toString)),
            ReservedKeys.PROOFS      -> ArrayValue(proofsData),
            ReservedKeys.MACHINES    -> machinesData,
            ReservedKeys.PARENT      -> parentData,
            ReservedKeys.CHILDREN    -> childrenData,
            ReservedKeys.SCRIPTS     -> scriptsData,
            ReservedKeys.HELD_ASSETS -> heldAssetsContext(fiber.fiberId)
          )
        )

      // === Script Context ===

      private def buildScriptContext(
        script: Records.ScriptFiberRecord,
        method: String,
        args:   JsonLogicValue
      ): F[JsonLogicValue] =
        MapValue(
          Map(
            ReservedKeys.METHOD -> StrValue(method),
            ReservedKeys.ARGS   -> args,
            ReservedKeys.STATE  -> script.stateData.getOrElse(NullValue)
          )
        ).pure[F]

      // === Trigger Context (simplified, for spawns) ===

      def buildTriggerContext(
        fiber: Records.StateMachineFiberRecord,
        input: FiberInput
      ): F[JsonLogicValue] =
        for {
          parentData   <- buildParentContext(fiber)
          childrenData <- buildChildrenContext(fiber)
        } yield MapValue(
          Map(
            ReservedKeys.STATE              -> fiber.stateData,
            ReservedKeys.EVENT              -> input.content,
            ReservedKeys.EVENT_NAME         -> StrValue(input.key),
            ReservedKeys.MACHINE_ID         -> StrValue(fiber.fiberId.toString),
            ReservedKeys.CURRENT_STATE_ID   -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER    -> IntValue(fiber.sequenceNumber.value.value),
            ReservedKeys.ORDINAL            -> IntValue(ordinal.value.value),
            ReservedKeys.LAST_SNAPSHOT_HASH -> StrValue(lastSnapshotHash.value),
            ReservedKeys.EPOCH_PROGRESS     -> IntValue(epochProgress.value.value),
            ReservedKeys.PARENT             -> parentData,
            ReservedKeys.CHILDREN           -> childrenData,
            ReservedKeys.HELD_ASSETS        -> heldAssetsContext(fiber.fiberId)
          )
        )

      // === Shared Context Builders ===

      private def buildProofsContext(proofs: List[SignatureProof]): F[List[MapValue]] =
        proofs.traverse { case SignatureProof(id, sig) =>
          id.toAddress.map { address =>
            MapValue(
              Map(
                ReservedKeys.ADDRESS   -> StrValue(address.show),
                ReservedKeys.ID        -> StrValue(id.hex.value),
                ReservedKeys.SIGNATURE -> StrValue(sig.value.value)
              )
            )
          }
        }

      /**
       * Generic helper for resolving a collection of IDs to a MapValue of summaries.
       *
       * @param ids Collection of UUIDs to resolve
       * @param lookup Function to look up records by ID
       * @param summary Function to convert a record to a JsonLogicValue summary
       * @return MapValue where keys are UUID strings and values are summaries
       */
      private def resolveFibers[A](
        ids:     Iterable[UUID],
        lookup:  UUID => Option[A],
        summary: A => JsonLogicValue
      ): F[MapValue] =
        ids.toList
          .flatTraverse { id =>
            OptionT
              .fromOption[F](lookup(id))
              .map(a => List(id.toString -> summary(a)))
              .getOrElse(List.empty)
          }
          .map(pairs => MapValue(pairs.toMap))

      private def buildMachinesContext(dependencies: Set[UUID]): F[MapValue] =
        resolveFibers(
          dependencies,
          calculatedState.stateMachines.get,
          (f: Records.StateMachineFiberRecord) => buildFiberSummary(f)
        )

      private def buildParentContext(fiber: Records.StateMachineFiberRecord): F[JsonLogicValue] =
        OptionT
          .fromOption[F](fiber.parentFiberId)
          .flatMap(parentId => OptionT.fromOption[F](calculatedState.stateMachines.get(parentId)))
          .map(parentFiber => buildFiberSummary(parentFiber, includeId = true): JsonLogicValue)
          .getOrElse(NullValue: JsonLogicValue)

      private def buildChildrenContext(fiber: Records.StateMachineFiberRecord): F[MapValue] =
        resolveFibers(
          fiber.childFiberIds,
          calculatedState.stateMachines.get,
          (f: Records.StateMachineFiberRecord) => buildFiberSummary(f)
        )

      private def buildScriptsContext(dependencies: Set[UUID]): F[MapValue] =
        resolveFibers(dependencies, calculatedState.scripts.get, buildScriptSummary)

      // === Summary Builders (reused across contexts) ===

      private def buildFiberSummary(
        fiber:     Records.StateMachineFiberRecord,
        includeId: Boolean = false
      ): MapValue = {
        val baseMap = Map(
          ReservedKeys.STATE            -> fiber.stateData,
          ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
          ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber.value.value),
          // FiberPolicy version & compatibility family: ALWAYS-present, well-typed `_policy` projection (H1 —
          // never absent, never wrong-typed) so a consumer's `depVersionAtLeast` / `depSupportsInterface` guard
          // reads totally and fails closed. `version` is the VERIFIED `schemaBinding.version` (#37-pinned, a
          // producer cannot lie about it), projected as a MAP {major,minor,patch} (D2 — JLVM has no
          // integer-indexed `get`); `{}` when the producer is unbound. `interfaces` is the self-declared set
          // (ERC-165 — TRUST-LAYER only; consumers MUST NOT gate funds/authority on it), ALWAYS an Array.
          ReservedKeys.POLICY -> policySummary(fiber)
        )
        val fullMap =
          if (includeId) baseMap + (ReservedKeys.MACHINE_ID -> StrValue(fiber.fiberId.toString))
          else baseMap
        MapValue(fullMap)
      }

      /** The `_policy` projection: VERIFIED version map (from schemaBinding) + self-declared interfaces array. */
      private def policySummary(fiber: Records.StateMachineFiberRecord): MapValue = {
        val versionValue: JsonLogicValue =
          fiber.schemaBinding.fold(MapValue(Map.empty): JsonLogicValue) { b =>
            MapValue(
              Map(
                ReservedKeys.POLICY_MAJOR -> IntValue(b.version.major),
                ReservedKeys.POLICY_MINOR -> IntValue(b.version.minor),
                ReservedKeys.POLICY_PATCH -> IntValue(b.version.patch)
              )
            )
          }
        val interfaces: List[JsonLogicValue] =
          fiber.definition.policy.dials
            .flatMap(_.interfaces)
            .getOrElse(Set.empty)
            .toList
            .sorted
            .map(StrValue(_))
        MapValue(
          Map(
            ReservedKeys.POLICY_VERSION    -> versionValue,
            ReservedKeys.POLICY_INTERFACES -> ArrayValue(interfaces)
          )
        )
      }

      private def buildScriptSummary(script: Records.ScriptFiberRecord): MapValue =
        MapValue(
          Map(
            ReservedKeys.STATE           -> script.stateData.getOrElse(NullValue),
            ReservedKeys.STATUS          -> StrValue(script.status.toString),
            ReservedKeys.SEQUENCE_NUMBER -> IntValue(script.sequenceNumber.value.value),
            ReservedKeys.LAST_INVOCATION -> script.lastInvocation.map(buildInvocationSummary).getOrElse(NullValue)
          )
        )

      private def buildInvocationSummary(inv: ScriptInvocation): MapValue =
        MapValue(
          Map(
            ReservedKeys.METHOD     -> StrValue(inv.method),
            ReservedKeys.ARGS       -> inv.args,
            ReservedKeys.RESULT     -> inv.result,
            ReservedKeys.GAS_USED   -> IntValue(inv.gasUsed),
            ReservedKeys.INVOKED_AT -> IntValue(inv.invokedAt.value.value),
            ReservedKeys.INVOKED_BY -> StrValue(inv.invokedBy.show)
          )
        )
    }
}
