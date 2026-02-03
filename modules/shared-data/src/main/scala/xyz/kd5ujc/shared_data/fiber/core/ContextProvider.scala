package xyz.kd5ujc.shared_data.fiber.core

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.FiberLogEntry.OracleInvocation
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
 * - scripts: dependent oracle states
 *
 * For oracles, context includes:
 * - _method: method name
 * - _args: method arguments
 * - _state: current oracle state
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
   */
  def make[F[_]: Async: SecurityProvider](calculatedState: CalculatedState): ContextProvider[F] =
    new ContextProvider[F] {

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

        case oracle: Records.ScriptFiberRecord =>
          input match {
            case _: FiberInput.MethodCall =>
              buildOracleContext(oracle, input.key, input.content)
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
          oraclesData  <- buildOraclesContext(dependencies)
        } yield MapValue(
          Map(
            ReservedKeys.STATE            -> fiber.stateData,
            ReservedKeys.EVENT            -> payload,
            ReservedKeys.EVENT_NAME       -> StrValue(eventName),
            ReservedKeys.MACHINE_ID       -> StrValue(fiber.fiberId.toString),
            ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber.value.value),
            ReservedKeys.PROOFS           -> ArrayValue(proofsData),
            ReservedKeys.MACHINES         -> machinesData,
            ReservedKeys.PARENT           -> parentData,
            ReservedKeys.CHILDREN         -> childrenData,
            ReservedKeys.SCRIPT_ORACLES   -> oraclesData
          )
        )

      // === Oracle Context ===

      private def buildOracleContext(
        oracle: Records.ScriptFiberRecord,
        method: String,
        args:   JsonLogicValue
      ): F[JsonLogicValue] =
        MapValue(
          Map(
            ReservedKeys.METHOD -> StrValue(method),
            ReservedKeys.ARGS   -> args,
            ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
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
            ReservedKeys.STATE            -> fiber.stateData,
            ReservedKeys.EVENT            -> input.content,
            ReservedKeys.EVENT_NAME       -> StrValue(input.key),
            ReservedKeys.MACHINE_ID       -> StrValue(fiber.fiberId.toString),
            ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber.value.value),
            ReservedKeys.PARENT           -> parentData,
            ReservedKeys.CHILDREN         -> childrenData
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

      private def buildOraclesContext(dependencies: Set[UUID]): F[MapValue] =
        resolveFibers(dependencies, calculatedState.scripts.get, buildOracleSummary)

      // === Summary Builders (reused across contexts) ===

      private def buildFiberSummary(
        fiber:     Records.StateMachineFiberRecord,
        includeId: Boolean = false
      ): MapValue = {
        val baseMap = Map(
          ReservedKeys.STATE            -> fiber.stateData,
          ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
          ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber.value.value)
        )
        val fullMap =
          if (includeId) baseMap + (ReservedKeys.MACHINE_ID -> StrValue(fiber.fiberId.toString))
          else baseMap
        MapValue(fullMap)
      }

      private def buildOracleSummary(oracle: Records.ScriptFiberRecord): MapValue =
        MapValue(
          Map(
            ReservedKeys.STATE           -> oracle.stateData.getOrElse(NullValue),
            ReservedKeys.STATUS          -> StrValue(oracle.status.toString),
            ReservedKeys.SEQUENCE_NUMBER -> IntValue(oracle.sequenceNumber.value.value),
            ReservedKeys.LAST_INVOCATION -> oracle.lastInvocation.map(buildInvocationSummary).getOrElse(NullValue)
          )
        )

      private def buildInvocationSummary(inv: OracleInvocation): MapValue =
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
