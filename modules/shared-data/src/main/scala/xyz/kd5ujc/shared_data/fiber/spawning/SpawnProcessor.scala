package xyz.kd5ujc.shared_data.fiber.spawning

import java.util.UUID

import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasLimit
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.{FailureReason, FiberContext, FiberOrdinal, FiberStatus, SpawnDirective}
import xyz.kd5ujc.shared_data.fiber.core._

/**
 * Processes spawn directives to create child fibers.
 *
 * Only applicable to state machines (oracles don't spawn children).
 *
 * Processing flow:
 * 1. Validate all directives using SpawnValidator (collect all errors)
 * 2. If validation passes, create fiber records for each validated spawn
 * 3. Return clear error messages for validation failures
 *
 * All expression evaluations (childId, owners, initialData) are gas-metered via StateT.
 */
trait SpawnProcessor[G[_]] {

  def processSpawns(
    directives:  List[SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    contextData: JsonLogicValue
  ): G[List[Records.StateMachineFiberRecord]]

  /**
   * Validates and processes spawn directives with gas metering via StateT.
   *
   * Gas is charged automatically to execution state.
   */
  def processSpawnsValidated(
    directives:  List[SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    contextData: JsonLogicValue,
    knownFibers: Set[UUID]
  ): G[Either[NonEmptyList[FailureReason], List[Records.StateMachineFiberRecord]]]
}

object SpawnProcessor {

  /**
   * Create SpawnProcessor that charges gas via StateT and reads config from FiberContext via Ask.
   */
  def make[F[_]: Async, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): SpawnProcessor[G] =
    new SpawnProcessor[G] {

      private val validator: SpawnValidator[G] = SpawnValidator.make[F, G]

      def processSpawns(
        directives:  List[SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): G[List[Records.StateMachineFiberRecord]] =
        processSpawnsValidated(directives, parent, contextData, Set.empty).flatMap {
          case Right(fibers) => fibers.pure[G]
          case Left(_) =>
            List.empty[Records.StateMachineFiberRecord].pure[G]
        }

      def processSpawnsValidated(
        directives:  List[SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        knownFibers: Set[UUID]
      ): G[Either[NonEmptyList[FailureReason], List[Records.StateMachineFiberRecord]]] =
        directives.isEmpty
          .pure[G]
          .ifM(
            ifTrue = List.empty[Records.StateMachineFiberRecord].asRight[NonEmptyList[FailureReason]].pure[G],
            ifFalse = validateAndProcess(directives, parent, contextData, knownFibers)
          )

      private def validateAndProcess(
        directives:  List[SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        knownFibers: Set[UUID]
      ): G[Either[NonEmptyList[FailureReason], List[Records.StateMachineFiberRecord]]] =
        validator.validateSpawns(directives, parent, knownFibers, contextData).flatMap {
          case Validated.Valid(plan) =>
            createFibersFromPlan(plan, parent, contextData).map(_.asRight[NonEmptyList[FailureReason]])

          case Validated.Invalid(errors) =>
            errors.asLeft[List[Records.StateMachineFiberRecord]].pure[G]
        }

      private def createFibersFromPlan(
        plan:        SpawnPlan,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): G[List[Records.StateMachineFiberRecord]] =
        plan.validatedSpawns.traverse(spawn => createFiberRecord(spawn, parent, contextData))

      private def createFiberRecord(
        spawn:       ValidatedSpawn,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): G[Records.StateMachineFiberRecord] =
        for {
          remainingGas <- ExecutionOps.remainingGas[G]
          gasConfig    <- ExecutionOps.askGasConfig[G]
          ordinal      <- ExecutionOps.askOrdinal[G]

          evalResult <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluateWithGas(spawn.directive.initialData, contextData, None, GasLimit(remainingGas), gasConfig)
            .flatMap(Async[F].fromEither)
            .liftTo[G]
          _ <- ExecutionOps.chargeGas[G](evalResult.gasUsed.amount)
          initialData = evalResult.value

          initialDataHash <- initialData.computeDigest.liftTo[G]

          childFiber = Records.StateMachineFiberRecord(
            fiberId = spawn.childId,
            creationOrdinal = ordinal,
            previousUpdateOrdinal = ordinal,
            latestUpdateOrdinal = ordinal,
            definition = spawn.directive.definition,
            currentState = spawn.directive.definition.initialState,
            stateData = initialData,
            stateDataHash = initialDataHash,
            sequenceNumber = FiberOrdinal.MinValue,
            owners = spawn.resolvedOwners,
            status = FiberStatus.Active,
            parentFiberId = Some(parent.fiberId)
          )
        } yield childFiber
    }
}
