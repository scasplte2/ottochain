package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, MapValue, StrValue}

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.lifecycle.combine.CombineRejected

import io.circe.syntax._

/**
 * Extracts side effects from transition effect results.
 *
 * Effect results can contain special keys that signal side effects:
 * - _triggers: Cross-machine event triggers
 * - _scriptCall: Script invocation
 * - _outputs: External system outputs
 * - _spawn: Child machine creation
 *
 * Supports two result formats:
 * - MapValue: Direct key lookup (e.g., `{"_triggers": [...]}`)
 * - ArrayValue: Tuple format (e.g., `[["_triggers", [...]]]`)
 */
object EffectExtractor {

  /**
   * Extract a value from an effect result by reserved key.
   * Handles both MapValue (direct key) and ArrayValue (tuple format) representations.
   */
  private def extractByKey(effectResult: JsonLogicValue, key: String): Option[JsonLogicValue] =
    effectResult match {
      case MapValue(map) =>
        map.get(key)

      case ArrayValue(updates) =>
        updates.collectFirst {
          case ArrayValue(List(StrValue(k), value)) if k == key =>
            value
        }

      case _ => None
    }

  /**
   * Extract an array value from an effect result by reserved key.
   * Returns empty list if key not found or value is not an array.
   */
  private def extractArrayByKey(effectResult: JsonLogicValue, key: String): List[JsonLogicValue] =
    extractByKey(effectResult, key).collect { case ArrayValue(items) => items }.getOrElse(List.empty)

  /**
   * Extract ALL side effects from a transition's effect result + expression as a single ordered list
   * of typed [[FiberEffect]]s. `_triggers` and `_scriptCall` become `Triggered`; `_spawn` directives
   * become `Spawned`; `_emit` events become `Emitted`; `_transferAsset` directives become `AssetTransferred`.
   *
   * Order matches the prior per-key extraction (triggers, then script call, then spawns, then emitted, then
   * asset transfers), and gas for payload/args/directive evaluation is charged in that order via
   * [[MeteredEvaluator]].
   */
  def extractEffects[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    effectExpr:    JsonLogicExpression,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[FiberEffect]] =
    for {
      triggers       <- extractTriggerEvents[F, G](effectResult, contextData, sourceFiberId)
      scriptCall     <- extractScriptCall[F, G](effectResult, contextData, sourceFiberId)
      assetTransfers <- extractAssetTransfers[F, G](effectResult, contextData)
      depMutations   <- extractDependencyMutations[F, G](effectResult, contextData)
    } yield {
      val spawns = extractSpawnDirectivesFromExpression(effectExpr)
      // Fix (1): stamp the EMITTING fiber id into every emitted event at extraction. `sourceFiberId` is the
      // fiber whose transition produced this effect result — the emitter (distinct from any cross-fiber caller).
      val emitted = extractEmittedEvents(effectResult, sourceFiberId)
      (triggers ++ scriptCall.toList).map(FiberEffect.Triggered) ++
      spawns.map(FiberEffect.Spawned) ++
      emitted.map(FiberEffect.Emitted) ++
      assetTransfers ++
      depMutations
    }

  /**
   * Extract trigger events with gas metering via StateT.
   *
   * Gas is charged to the execution state automatically via Stateful.
   * Gas limit and config are read from FiberContext via Ask.
   *
   * @param effectResult  The result from effect evaluation containing trigger definitions
   * @param contextData   Context data for evaluating payload expressions
   * @param sourceFiberId The fiber that is emitting these triggers
   * @return List of triggers (gas charged via state)
   */
  def extractTriggerEvents[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[FiberTrigger]] =
    extractArrayByKey(effectResult, ReservedKeys.TRIGGERS)
      .flatTraverse { item =>
        parseTriggerEvent[F, G](item, contextData, sourceFiberId)
          .map(_.toList)
      }

  private def parseTriggerEvent[F[_]: Async, G[_]: Monad](
    value:         JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[Option[FiberTrigger]] =
    value match {
      case MapValue(triggerMap) =>
        (for {
          targetIdStr <- OptionT.fromOption[G](
            triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect { case StrValue(id) => id }
          )
          targetId <- OptionT.fromOption[G](scala.util.Try(UUID.fromString(targetIdStr)).toOption)
          eventType <- OptionT.fromOption[G](
            triggerMap.get(ReservedKeys.EVENT_NAME).collect { case StrValue(et) => et }
          )
          payloadValue <- OptionT.fromOption[G](triggerMap.get(ReservedKeys.PAYLOAD))
          payloadExpr = ExpressionParser.valueToExpression(payloadValue)
          evaluatedPayload <- OptionT(
            MeteredEvaluator.evalOpt[F, G](payloadExpr, contextData, GasExhaustionPhase.Trigger)
          )
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(eventType, evaluatedPayload),
          sourceFiberId = Some(sourceFiberId)
        )).value
      case _ => none[FiberTrigger].pure[G]
    }

  /**
   * Extract script call with gas metering via StateT.
   *
   * Gas is charged to the execution state automatically via Stateful.
   * Gas limit and config are read from FiberContext via Ask.
   *
   * @param effectResult  The result from effect evaluation containing script call definition
   * @param contextData   Context data for evaluating args expressions
   * @param sourceFiberId The fiber that is making this script call
   * @return Optional script trigger (gas charged via state)
   */
  def extractScriptCall[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[Option[FiberTrigger]] =
    extractByKey(effectResult, ReservedKeys.SCRIPT_CALL) match {
      case Some(MapValue(scriptCallMap)) =>
        (for {
          cidStr <- OptionT.fromOption[G](scriptCallMap.get(ReservedKeys.FIBER_ID).collect { case StrValue(id) => id })
          targetId  <- OptionT.fromOption[G](scala.util.Try(UUID.fromString(cidStr)).toOption)
          method    <- OptionT.fromOption[G](scriptCallMap.get(ReservedKeys.METHOD).collect { case StrValue(m) => m })
          argsValue <- OptionT.fromOption[G](scriptCallMap.get(ReservedKeys.ARGS))
          argsExpr = ExpressionParser.valueToExpression(argsValue)
          evaluatedArgs <- OptionT(MeteredEvaluator.evalOpt[F, G](argsExpr, contextData, GasExhaustionPhase.Trigger))
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(method, evaluatedArgs),
          sourceFiberId = Some(sourceFiberId)
        )).value

      case _ => none[FiberTrigger].pure[G]
    }

  /**
   * Extract fiber-held asset custody transfers (`_transferAsset`, asset-model.md §10) with gas metering via
   * StateT. Mirrors [[extractTriggerEvents]] EXACTLY: the directive's `assetId`/`recipient` JSON-Logic
   * expressions are RESOLVED here (against the transition context), so the resulting [[FiberEffect.AssetTransferred]]
   * carries values, not logic. Gas is charged under [[GasExhaustionPhase.Morphism]]. The `recipient` is the
   * canonical [[AssetHolder]] OBJECT form ONLY (`{"Fiber":{"fiberId":..}}` / `{"Wallet":{"address":..}}`) —
   * see [[parseAssetTransfer]]. A malformed directive (non-UUID `assetId`, a `recipient` that is not a
   * well-formed `AssetHolder` object, or a gas/eval failure) raises a graceful [[CombineRejected]] — NOT a
   * silent drop; surfacing the parse failure is deliberate (a silently-dropped transfer is a latent bug).
   *
   * SECURITY: this extractor carries NO authorization. The combiner ([[xyz.kd5ujc.shared_data.lifecycle.combine.AssetCombiner.applyFiberTransfer]])
   * enforces `holder == AssetHolder.Fiber(emittingFiberId)` before applying any extracted transfer (R1).
   */
  def extractAssetTransfers[F[_]: Async, G[_]: Monad](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[List[FiberEffect.AssetTransferred]] =
    extractArrayByKey(effectResult, ReservedKeys.TRANSFER_ASSET)
      .traverse(item => parseAssetTransfer[F, G](item, contextData))

  /**
   * Parse one `_transferAsset` directive item into an [[FiberEffect.AssetTransferred]]. The `assetId` /
   * `recipient` expressions are RESOLVED here (gas charged under [[GasExhaustionPhase.Morphism]]) so the
   * effect carries values, not logic.
   *
   * The recipient is the canonical [[AssetHolder]] OBJECT form ONLY — `{"Fiber":{"fiberId":..}}` /
   * `{"Wallet":{"address":..}}` — decoded strictly through the same magnolia `AssetHolder` codec used on every
   * other surface (`MintAsset.holder`, `ApplyMorphism.recipient`, `AssetRecord.holder`). The old bare-string
   * UUID/DAG-address disambiguation is GONE. Any malformation — a non-object item, a missing/non-UUID
   * `assetId`, a recipient that is not a well-formed `AssetHolder` object, or a gas/eval failure — raises a
   * graceful [[CombineRejected]] (NOT a silent drop): extraction runs only on the combiner apply path, so the
   * raise is caught at `Combiner.insert` → `RejectionReceipt` (rule #2; the same authoritative-gate pattern
   * `AssetCombiner` already uses), never a block-acceptance `Invalid`. Surfacing the error is deliberate — a
   * silently-dropped transfer is a latent bug, not a no-op.
   */
  private def parseAssetTransfer[F[_]: Async, G[_]: Monad](
    value:       JsonLogicValue,
    contextData: JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[FiberEffect.AssetTransferred] =
    value match {
      case MapValue(transferMap) =>
        for {
          assetIdValue     <- requireField[F, G](transferMap, ReservedKeys.ASSET_ID, "assetId")
          evaluatedAssetId <- evalOrReject[F, G](assetIdValue, contextData, "assetId")
          assetId <- evaluatedAssetId match {
            case StrValue(s) =>
              scala.util.Try(UUID.fromString(s)).toOption match {
                case Some(uuid) => uuid.pure[G]
                case None       => rejectTransfer[F, G, UUID](s"assetId is not a valid UUID: '$s'")
              }
            case other => rejectTransfer[F, G, UUID](s"assetId must be a UUID string, got $other")
          }
          recipientValue     <- requireField[F, G](transferMap, ReservedKeys.RECIPIENT, "recipient")
          evaluatedRecipient <- evalOrReject[F, G](recipientValue, contextData, "recipient")
          recipient <- evaluatedRecipient match {
            case MapValue(_) =>
              evaluatedRecipient.asJson.as[AssetHolder] match {
                case Right(holder) => holder.pure[G]
                case Left(err) =>
                  rejectTransfer[F, G, AssetHolder](
                    s"recipient is not a valid AssetHolder object {Fiber|Wallet}: ${err.getMessage}"
                  )
              }
            case other =>
              rejectTransfer[F, G, AssetHolder](
                s"""recipient must be an AssetHolder object {"Fiber":{"fiberId":..}} / {"Wallet":{"address":..}}, got $other"""
              )
          }
        } yield FiberEffect.AssetTransferred(assetId = assetId, recipient = recipient)
      case other =>
        rejectTransfer[F, G, FiberEffect.AssetTransferred](s"malformed item (expected an object), got $other")
    }

  /** Look up a required `_transferAsset` field, or raise a graceful [[CombineRejected]] naming what is missing. */
  private def requireField[F[_]: Async, G[_]: Monad](
    map:   Map[String, JsonLogicValue],
    key:   String,
    label: String
  )(implicit lift: F ~> G): G[JsonLogicValue] =
    map.get(key) match {
      case Some(v) => v.pure[G]
      case None    => rejectTransfer[F, G, JsonLogicValue](s"missing $label")
    }

  /** Evaluate a `_transferAsset` sub-expression under the Morphism gas phase; a `Left` (gas/eval failure) is LOUD. */
  private def evalOrReject[F[_]: Async, G[_]: Monad](
    value:       JsonLogicValue,
    contextData: JsonLogicValue,
    label:       String
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[JsonLogicValue] =
    MeteredEvaluator
      .eval[F, G](ExpressionParser.valueToExpression(value), contextData, GasExhaustionPhase.Morphism)
      .flatMap {
        case Right(v)     => v.pure[G]
        case Left(reason) => rejectTransfer[F, G, JsonLogicValue](s"$label did not evaluate: $reason")
      }

  /** Raise a graceful, combiner-caught [[CombineRejected]] for a malformed `_transferAsset` directive. */
  private def rejectTransfer[F[_]: Async, G[_], A](reason: String)(implicit lift: F ~> G): G[A] =
    lift(Async[F].raiseError[A](CombineRejected(s"_transferAsset: $reason")))

  /**
   * Extract dynamic-dependency mutations (`_addDependency` / `_setDependencyActive`) with gas metering.
   * Mirrors [[extractAssetTransfers]]: each directive's `fiberId` JSON-Logic expression is RESOLVED here
   * against the transition context (gas charged under [[GasExhaustionPhase.DependencyMutation]]), so the
   * resulting [[FiberEffect.DependencyMutated]] carries a value, not logic. `_addDependency` forces
   * `active = true`; `_setDependencyActive` reads the directive's `active` boolean. A malformed directive
   * (missing/non-UUID `fiberId`, or a `_setDependencyActive` lacking a boolean `active`) is DROPPED — the
   * same fail-silent mode the other extractors use. The append-only ledger + DoS bounds are applied later,
   * by the engine ([[xyz.kd5ujc.shared_data.fiber.core.DependencyLedger]]).
   */
  def extractDependencyMutations[F[_]: Async, G[_]: Monad](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[List[FiberEffect.DependencyMutated]] =
    for {
      adds <- extractArrayByKey(effectResult, ReservedKeys.ADD_DEPENDENCY)
        .flatTraverse(item => parseDependencyMutation[F, G](item, contextData, forcedActive = Some(true)).map(_.toList))
      sets <- extractArrayByKey(effectResult, ReservedKeys.SET_DEPENDENCY_ACTIVE)
        .flatTraverse(item => parseDependencyMutation[F, G](item, contextData, forcedActive = None).map(_.toList))
    } yield adds ++ sets

  private def parseDependencyMutation[F[_]: Async, G[_]: Monad](
    value:        JsonLogicValue,
    contextData:  JsonLogicValue,
    forcedActive: Option[Boolean]
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[Option[FiberEffect.DependencyMutated]] =
    value match {
      case MapValue(depMap) =>
        (for {
          fiberIdValue <- OptionT.fromOption[G](depMap.get(ReservedKeys.FIBER_ID))
          fiberIdExpr = ExpressionParser.valueToExpression(fiberIdValue)
          // Charge gas for fiberId resolution under the DependencyMutation phase.
          evaluatedFiberId <- OptionT(
            MeteredEvaluator.evalOpt[F, G](fiberIdExpr, contextData, GasExhaustionPhase.DependencyMutation)
          )
          fiberIdStr <- OptionT.fromOption[G](evaluatedFiberId match { case StrValue(s) => Some(s); case _ => None })
          fiberId    <- OptionT.fromOption[G](scala.util.Try(UUID.fromString(fiberIdStr)).toOption)
          active <- OptionT.fromOption[G](
            forcedActive.orElse(depMap.get(ReservedKeys.ACTIVE).collect { case BoolValue(b) => b })
          )
        } yield FiberEffect.DependencyMutated(fiberId, active)).value
      case _ => none[FiberEffect.DependencyMutated].pure[G]
    }

  /**
   * Fix (1) — `_emit` emitter-stamping. `.zipWithIndex` runs over the RAW `_emit` array BEFORE the
   * `flatMap`-drop, so `emissionIndex` is the true authoring-time position; a malformed sibling that
   * `parseEmittedEvent` drops leaves a SPARSE gap (survivors keep their original positions). `emitterFiberId`
   * is the engine-supplied id of the fiber whose transition ran `_emit` — never user-supplied, so the stamp
   * cannot be forged from inside a guard/effect expression.
   */
  def extractEmittedEvents(effectResult: JsonLogicValue, emitterFiberId: UUID): List[EmittedEvent] =
    extractArrayByKey(effectResult, ReservedKeys.EMIT).zipWithIndex.flatMap { case (v, i) =>
      parseEmittedEvent(v, emitterFiberId, i)
    }

  private def parseEmittedEvent(value: JsonLogicValue, emitterFiberId: UUID, emissionIndex: Int): Option[EmittedEvent] =
    value match {
      case MapValue(m) =>
        for {
          name <- m.get(ReservedKeys.NAME).collect { case StrValue(n) => n }
          data <- m.get(ReservedKeys.DATA)
          destination = m.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
        } yield EmittedEvent(name, data, destination, emitterFiberId, emissionIndex)
      case _ => None
    }

  def extractSpawnDirectivesFromExpression(effectExpr: JsonLogicExpression): List[SpawnDirective] =
    effectExpr match {
      case MapExpression(map) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayExpression(spawns) =>
            spawns.flatMap(parseSpawnFromExpression)
          }
          .getOrElse(List.empty)

      case ConstExpression(MapValue(map)) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayValue(spawns) =>
            spawns.flatMap(parseSpawnFromValue)
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  private def parseSpawnFromExpression(expr: JsonLogicExpression): Option[SpawnDirective] =
    expr match {
      case MapExpression(spawnMap) =>
        for {
          childIdExpr     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defExpr         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition      <- ExpressionParser.parseStateMachineDefinitionFromExpression(defExpr)
          initialDataExpr <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield SpawnDirective(
          childIdExpr = childIdExpr,
          definition = definition,
          initialData = initialDataExpr,
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS)
        )
      case _ => None
    }

  private def parseSpawnFromValue(value: JsonLogicValue): Option[SpawnDirective] =
    value match {
      case MapValue(spawnMap) =>
        for {
          childIdValue     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defValue         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition       <- ExpressionParser.parseStateMachineDefinition(defValue)
          initialDataValue <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield SpawnDirective(
          childIdExpr = ConstExpression(childIdValue),
          definition = definition,
          initialData = ConstExpression(initialDataValue),
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS).map(ConstExpression(_))
        )
      case _ => None
    }
}
