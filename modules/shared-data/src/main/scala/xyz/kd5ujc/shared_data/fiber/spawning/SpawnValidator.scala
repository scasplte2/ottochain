package xyz.kd5ujc.shared_data.fiber.spawning

import java.util.UUID

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{~>, Monad}

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.core.{ArrayValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasLimit
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.{FailureReason, FiberContext, GasExhaustionPhase, SpawnDirective}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.syntax.all._

import eu.timepit.refined.refineV

/**
 * Pre-validates spawn directives before execution.
 *
 * Collects all validation errors rather than failing on first error,
 * enabling better error messages and avoiding partial execution.
 *
 * Gas is charged via StateT automatically during evaluation.
 */
trait SpawnValidator[G[_]] {

  /**
   * Validates spawn directives with gas metering via StateT.
   *
   * Gas is charged to execution state automatically, even on validation failure.
   */
  def validateSpawns(
    directives:  List[SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    knownFibers: Set[UUID],
    contextData: JsonLogicValue
  ): G[ValidatedNel[FailureReason, SpawnPlan]]
}

/**
 * Validated spawn plan ready for execution.
 *
 * @param validatedSpawns List of directives with pre-resolved child IDs and owners
 */
final case class SpawnPlan(
  validatedSpawns: List[ValidatedSpawn]
)

/**
 * A spawn directive with pre-validated and resolved values.
 *
 * @param directive     The original spawn directive
 * @param childId       Pre-resolved child ID (validated as UUID)
 * @param resolvedOwners Pre-resolved owners (validated addresses)
 */
final case class ValidatedSpawn(
  directive:      SpawnDirective,
  childId:        UUID,
  resolvedOwners: Set[Address]
)

object SpawnValidator {

  /**
   * Create a SpawnValidator that charges gas via StateT and reads config from FiberContext via Ask.
   */
  def make[F[_]: Async, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): SpawnValidator[G] =
    new SpawnValidator[G] {

      def validateSpawns(
        directives:  List[SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        knownFibers: Set[UUID],
        contextData: JsonLogicValue
      ): G[ValidatedNel[FailureReason, SpawnPlan]] =
        directives
          .traverse(directive => validateSingle(directive, parent, contextData))
          .map(_.sequence.andThen(validateBatchConstraints(_, knownFibers)))

      private def validateSingle(
        directive:   SpawnDirective,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): G[ValidatedNel[FailureReason, ValidatedSpawn]] =
        for {
          childIdResult <- evaluateChildId(directive, contextData)
          ownersResult  <- evaluateOwners(directive, parent, contextData)
        } yield (childIdResult, ownersResult).mapN { case (childId, owners) =>
          ValidatedSpawn(directive, childId, owners)
        }

      private def evaluateChildId(
        directive:   SpawnDirective,
        contextData: JsonLogicValue
      ): G[ValidatedNel[FailureReason, UUID]] =
        for {
          remaining <- ExecutionOps.remainingGas[G]
          gasConfig <- ExecutionOps.askGasConfig[G]
          evalResult <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluateWithGas(directive.childIdExpr, contextData, None, GasLimit(remaining), gasConfig)
            .liftTo[G]
          validated <- evalResult match {
            case Right(result) =>
              ExecutionOps.chargeGas[G](result.gasUsed.amount).as {
                result.value match {
                  case StrValue(idStr) =>
                    scala.util.Try(UUID.fromString(idStr)).toOption match {
                      case Some(uuid) => Validated.validNel(uuid)
                      case None =>
                        Validated.invalidNel(
                          FailureReason.InvalidChildIdFormat(idStr, "Not a valid UUID format")
                        )
                    }
                  case other =>
                    Validated.invalidNel(
                      FailureReason.InvalidChildIdFormat(
                        other.toString.take(50),
                        "Expected string value"
                      )
                    )
                }
              }

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Spawn)
                .map(reason => Validated.invalidNel[FailureReason, UUID](reason))
          }
        } yield validated

      private def evaluateOwners(
        directive:   SpawnDirective,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): G[ValidatedNel[FailureReason, Set[Address]]] =
        directive.ownersExpr match {
          case None =>
            Validated.validNel[FailureReason, Set[Address]](parent.owners).pure[G]

          case Some(expr) =>
            for {
              remaining <- ExecutionOps.remainingGas[G]
              gasConfig <- ExecutionOps.askGasConfig[G]
              evalResult <- JsonLogicEvaluator
                .tailRecursive[F]
                .evaluateWithGas(expr, contextData, None, GasLimit(remaining), gasConfig)
                .liftTo[G]
              validated <- evalResult match {
                case Right(result) =>
                  ExecutionOps.chargeGas[G](result.gasUsed.amount).as {
                    result.value match {
                      case ArrayValue(addresses) =>
                        addresses
                          .traverse[ValidatedNel[FailureReason, *], Address] {
                            case StrValue(addr) =>
                              refineV[DAGAddressRefined](addr) match {
                                case Right(refined) => Validated.validNel(Address(refined))
                                case Left(err) =>
                                  Validated.invalidNel(FailureReason.InvalidOwnerAddress(addr, err))
                              }
                            case other =>
                              Validated.invalidNel(
                                FailureReason.InvalidOwnerAddress(
                                  other.toString.take(30),
                                  "Expected string address"
                                )
                              )
                          }
                          .map(_.toSet)

                      case other =>
                        Validated.invalidNel(
                          FailureReason.InvalidOwnersExpression(
                            "Expected array of addresses, got non-array value"
                          )
                        )
                    }
                  }

                case Left(ex) =>
                  ex.toFailureReason[G](GasExhaustionPhase.Spawn)
                    .map(reason => Validated.invalidNel[FailureReason, Set[Address]](reason))
              }
            } yield validated
        }

      private def validateBatchConstraints(
        spawns:      List[ValidatedSpawn],
        knownFibers: Set[UUID]
      ): ValidatedNel[FailureReason, SpawnPlan] = {
        val childIds = spawns.map(_.childId)

        val duplicateErrors: List[FailureReason] = childIds
          .groupBy(identity)
          .collect {
            case (id, occurrences) if occurrences.size > 1 =>
              FailureReason.DuplicateChildId(id, occurrences.size)
          }
          .toList

        val collisionErrors: List[FailureReason] = childIds
          .filter(knownFibers.contains)
          .distinct
          .map(FailureReason.ChildIdCollision)

        val allErrors = duplicateErrors ++ collisionErrors

        NonEmptyList.fromList(allErrors) match {
          case Some(errors) => Validated.invalid(errors)
          case None         => Validated.validNel(SpawnPlan(spawns))
        }
      }
    }
}
