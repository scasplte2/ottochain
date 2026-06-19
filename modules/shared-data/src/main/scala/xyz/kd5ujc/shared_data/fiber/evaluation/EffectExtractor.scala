package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._

import eu.timepit.refined.refineV

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
      val emitted = extractEmittedEvents(effectResult)
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
   * carries values, not logic. Gas is charged under [[GasExhaustionPhase.Morphism]]. A malformed directive
   * (non-UUID `assetId`, or a `recipient` that resolves to neither a UUID nor a DAG address) is DROPPED —
   * the same fail-silent mode the other extractors use for malformed items.
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
      .flatTraverse { item =>
        parseAssetTransfer[F, G](item, contextData).map(_.toList)
      }

  private def parseAssetTransfer[F[_]: Async, G[_]: Monad](
    value:       JsonLogicValue,
    contextData: JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[Option[FiberEffect.AssetTransferred]] =
    value match {
      case MapValue(transferMap) =>
        (for {
          assetIdValue <- OptionT.fromOption[G](transferMap.get(ReservedKeys.ASSET_ID))
          assetIdExpr = ExpressionParser.valueToExpression(assetIdValue)
          // Charge gas for assetId resolution under the Morphism phase.
          evaluatedAssetId <- OptionT(
            MeteredEvaluator.evalOpt[F, G](assetIdExpr, contextData, GasExhaustionPhase.Morphism)
          )
          assetIdStr <- OptionT.fromOption[G](evaluatedAssetId match {
            case StrValue(s) => Some(s); case _ => None
          })
          assetId <- OptionT.fromOption[G](scala.util.Try(UUID.fromString(assetIdStr)).toOption)

          recipientValue <- OptionT.fromOption[G](transferMap.get(ReservedKeys.RECIPIENT))
          recipientExpr = ExpressionParser.valueToExpression(recipientValue)
          // Charge gas for recipient resolution under the Morphism phase.
          evaluatedRecipient <- OptionT(
            MeteredEvaluator.evalOpt[F, G](recipientExpr, contextData, GasExhaustionPhase.Morphism)
          )
          recipientStr <- OptionT.fromOption[G](evaluatedRecipient match {
            case StrValue(s) => Some(s); case _ => None
          })
          recipient <- OptionT.fromOption[G](parseRecipient(recipientStr))
        } yield FiberEffect.AssetTransferred(assetId = assetId, recipient = recipient)).value
      case _ => none[FiberEffect.AssetTransferred].pure[G]
    }

  /**
   * Disambiguate a recipient string into an [[AssetHolder]]: a UUID-shaped string is a fiber custody target
   * ([[AssetHolder.Fiber]]); otherwise a valid DAG address is a wallet ([[AssetHolder.Wallet]]). Neither →
   * `None` (malformed, dropped). UUID is tried FIRST so a UUID never accidentally validates as an address.
   */
  private def parseRecipient(s: String): Option[AssetHolder] =
    scala.util.Try(UUID.fromString(s)).toOption match {
      case Some(uuid) => Some(AssetHolder.Fiber(uuid))
      case None       => refineV[DAGAddressRefined](s).toOption.map(refined => AssetHolder.Wallet(Address(refined)))
    }

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

  def extractEmittedEvents(effectResult: JsonLogicValue): List[EmittedEvent] =
    extractArrayByKey(effectResult, ReservedKeys.EMIT).flatMap(parseEmittedEvent)

  private def parseEmittedEvent(value: JsonLogicValue): Option[EmittedEvent] =
    value match {
      case MapValue(m) =>
        for {
          name <- m.get(ReservedKeys.NAME).collect { case StrValue(n) => n }
          data <- m.get(ReservedKeys.DATA)
          destination = m.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
        } yield EmittedEvent(name, data, destination)
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
