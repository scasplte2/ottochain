package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{~>, Monad}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.StrValue
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasLimit
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._

/**
 * Extracts side effects from transition effect results.
 *
 * Effect results can contain special keys that signal side effects:
 * - _triggers: Cross-machine event triggers
 * - _oracleCall: Oracle invocation
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
          remaining <- OptionT.liftF(ExecutionOps.remainingGas[G])
          gasConfig <- OptionT.liftF(ExecutionOps.askGasConfig[G])
          evalResult <- OptionT(
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluateWithGas(payloadExpr, contextData, None, GasLimit(remaining), gasConfig)
              .map(_.toOption)
              .liftTo[G]
          )
          _ <- OptionT.liftF(ExecutionOps.chargeGas[G](evalResult.gasUsed.amount))
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(eventType, evalResult.value),
          sourceFiberId = Some(sourceFiberId)
        )).value
      case _ => none[FiberTrigger].pure[G]
    }

  /**
   * Extract oracle call with gas metering via StateT.
   *
   * Gas is charged to the execution state automatically via Stateful.
   * Gas limit and config are read from FiberContext via Ask.
   *
   * @param effectResult  The result from effect evaluation containing oracle call definition
   * @param contextData   Context data for evaluating args expressions
   * @param sourceFiberId The fiber that is making this oracle call
   * @return Optional oracle trigger (gas charged via state)
   */
  def extractOracleCall[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[Option[FiberTrigger]] =
    extractByKey(effectResult, ReservedKeys.ORACLE_CALL) match {
      case Some(MapValue(oracleCallMap)) =>
        (for {
          cidStr <- OptionT.fromOption[G](oracleCallMap.get(ReservedKeys.FIBER_ID).collect { case StrValue(id) => id })
          targetId  <- OptionT.fromOption[G](scala.util.Try(UUID.fromString(cidStr)).toOption)
          method    <- OptionT.fromOption[G](oracleCallMap.get(ReservedKeys.METHOD).collect { case StrValue(m) => m })
          argsValue <- OptionT.fromOption[G](oracleCallMap.get(ReservedKeys.ARGS))
          argsExpr = ExpressionParser.valueToExpression(argsValue)
          remaining <- OptionT.liftF(ExecutionOps.remainingGas[G])
          gasConfig <- OptionT.liftF(ExecutionOps.askGasConfig[G])
          evalResult <- OptionT(
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluateWithGas(argsExpr, contextData, None, GasLimit(remaining), gasConfig)
              .map(_.toOption)
              .liftTo[G]
          )
          _ <- OptionT.liftF(ExecutionOps.chargeGas[G](evalResult.gasUsed.amount))
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(method, evalResult.value),
          sourceFiberId = Some(sourceFiberId)
        )).value

      case _ => none[FiberTrigger].pure[G]
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
