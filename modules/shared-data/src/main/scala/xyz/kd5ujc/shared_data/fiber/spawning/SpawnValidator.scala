package xyz.kd5ujc.shared_data.fiber.spawning

import java.util.UUID

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.core.{ArrayValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasLimit
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.{
  ExecutionLimits,
  FailureReason,
  FiberContext,
  GasExhaustionPhase,
  SpawnDirective,
  SpawnOwnerPolicy
}
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
        // Fix (3): read the spawn fan-out cap from runtime config (Ask is in scope here, but NOT inside the
        // pure validateBatchConstraints) and pass it down. Reading it here keeps the trait signature and the
        // entire SpawnProcessor call chain unchanged.
        ExecutionOps.askLimits[G].flatMap { limits =>
          // FiberPolicy dial #1 (selfReproducing): hoist the parent's definition digest ONCE per transition
          // (the parent definition is invariant across the directive list; do NOT re-hash it per spawn — B5).
          // Only computed when the parent has actually opted in, so non-self-reproducing parents pay nothing.
          maybeSelfHash(parent).flatMap { selfHash =>
            directives
              .traverse(directive => validateSingle(directive, parent, contextData, selfHash))
              .map(_.sequence.andThen(validateBatchConstraints(_, knownFibers, limits, parent)))
          }
        }

      /** `Some(digest)` iff the parent has opted into self-reproduction; `None` otherwise (no hashing cost). */
      private def maybeSelfHash(parent: Records.StateMachineFiberRecord): G[Option[Hash]] =
        if (parent.definition.policy.exists(_.isSelfReproducing))
          parent.definition.computeDigest.liftTo[G].map(_.some)
        else none[Hash].pure[G]

      private def validateSingle(
        directive:   SpawnDirective,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        selfHash:    Option[Hash]
      ): G[ValidatedNel[FailureReason, ValidatedSpawn]] =
        for {
          childIdResult <- evaluateChildId(directive, contextData)
          ownersResult  <- evaluateOwners(directive, parent, contextData)
          selfRepResult <- validateSelfReproduction(directive, selfHash)
        } yield (childIdResult, ownersResult, selfRepResult).mapN { case (childId, owners, _) =>
          ValidatedSpawn(directive, childId, owners)
        }

      /**
       * FiberPolicy dial #1 — selfReproducing (code-preservation invariant, engine-hardening Part A). When the
       * parent has opted in (`selfHash = Some`), a `_spawn` child's `definition` MUST hash-equal the parent's
       * (compared via canonical digest, never structural equality — Map key order is canonicalized by the
       * codec). FAIL-CLOSED: a non-copy spawn aborts the whole transition. The invariant is TRANSITIVE: the
       * child's definition is byte-equal to the parent's, so it carries the same `policy.selfReproducing = true`
       * and can itself only spawn copies — the property holds for the entire lineage. The upgrade-latch in
       * `FiberPolicy.tightens` keeps it from being cleared by a migration, so a fiber cannot graduate out.
       */
      private def validateSelfReproduction(
        directive: SpawnDirective,
        selfHash:  Option[Hash]
      ): G[ValidatedNel[FailureReason, Unit]] =
        selfHash match {
          case None => Validated.validNel[FailureReason, Unit](()).pure[G] // opt-out default: untouched
          case Some(expected) =>
            directive.definition.computeDigest.liftTo[G].map { childHash =>
              if (childHash === expected) Validated.validNel[FailureReason, Unit](())
              else
                Validated.invalidNel[FailureReason, Unit](
                  FailureReason.PolicyViolation(
                    "selfReproducing",
                    s"spawned child definition digest ${childHash.value} != self digest ${expected.value}"
                  )
                )
            }
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
        resolveOwners(directive, parent, contextData).map(applySpawnOwnerPolicy(parent, _))

      /**
       * FiberPolicy dial `spawnOwnerPolicy` — constrain a child's resolved owners against the parent's.
       *
       * FAIL-CLOSED SUBSET FLOOR (audit 2026-07-07, finding H1 — root fix). A `_spawn` may assign UNSIGNED
       * child `owners` from an attacker-controlled `ownersExpr`, and a fiber-origin `_scriptCall` resolves the
       * script caller as the emitting fiber's owner (`TriggerHandler.handleScript`). Composed, that let an
       * attacker mint a child "owned by" an arbitrary VICTIM address and route a `_scriptCall` through it to
       * pass a script's `Whitelist`/`FiberOwned` access policy AS the victim, with no victim signature. The
       * floor blocks the forgery at spawn time: a spawned child's resolved owners MUST be a subset of the
       * PARENT's owners, so a child can never be owned by an address the parent does not already own. This
       * holds REGARDLESS of the dial — even the default/absent policy and `Explicit` are floored.
       *
       * The dial then only chooses HOW owners are derived, always WITHIN that floor:
       *   - `InheritParent` FORCES child owners := parent.owners (ignoring any expr) — trivially within-floor.
       *   - `Explicit`/absent and `SubsetOfParent` take whatever the owners expression produced, subject to the
       *     subset floor. With the floor now applied unconditionally the two are behaviourally identical;
       *     `SubsetOfParent` is retained as an explicit, self-documenting intent on the tighten-only lattice.
       * Applied AFTER resolution so it governs whatever the owners expression produced. `resolved.andThen`
       * short-circuits an already-Invalid resolution (e.g. a malformed owner address), so the floor never
       * masks the more specific `InvalidOwnerAddress`/`InvalidOwnersExpression` failure.
       */
      private def applySpawnOwnerPolicy(
        parent:   Records.StateMachineFiberRecord,
        resolved: ValidatedNel[FailureReason, Set[Address]]
      ): ValidatedNel[FailureReason, Set[Address]] =
        parent.definition.policy.flatMap(_.spawnOwnerPolicy) match {
          case Some(SpawnOwnerPolicy.InheritParent) => resolved.map(_ => parent.owners)
          case None | Some(SpawnOwnerPolicy.Explicit) | Some(SpawnOwnerPolicy.SubsetOfParent) =>
            resolved.andThen(subsetOfParentFloor(parent, _))
        }

      /**
       * The fail-closed subset floor: `owners ⊆ parent.owners`, else a `PolicyViolation("spawnOwnerPolicy", …)`
       * abort. The accept/reject decision (`subsetOf`) is set-membership and independent of iteration order;
       * the detail string sorts both address sets by their canonical string so the committed rejection receipt
       * text is deterministic (no Set-iteration-order dependence) across validators.
       */
      private def subsetOfParentFloor(
        parent: Records.StateMachineFiberRecord,
        owners: Set[Address]
      ): ValidatedNel[FailureReason, Set[Address]] =
        if (owners.subsetOf(parent.owners)) Validated.validNel(owners)
        else {
          def show(as: Set[Address]): String = as.toList.map(_.value.value).sorted.mkString("{", ",", "}")
          Validated.invalidNel(
            FailureReason.PolicyViolation(
              "spawnOwnerPolicy",
              s"child owners ${show(owners)} are not a subset of parent owners ${show(parent.owners)}"
            )
          )
        }

      private def resolveOwners(
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
        knownFibers: Set[UUID],
        limits:      ExecutionLimits,
        parent:      Records.StateMachineFiberRecord
      ): ValidatedNel[FailureReason, SpawnPlan] = {
        val childIds = spawns.map(_.childId)

        // Fix (3): fail-closed spawn fan-out bound. An over-limit batch yields an Invalid here, which
        // propagates Left(NonEmptyList[FailureReason]) → TransactionResult.Aborted (total discard) BEFORE any
        // child record is constructed (createFibersFromPlan runs only on a valid SpawnPlan) and before the
        // per-spawn initialData gas burn. This is an abort, not a silent drop of the excess.
        val countErrors: List[FailureReason] =
          if (spawns.size > limits.maxSpawnsPerTransition)
            List(FailureReason.SpawnLimitExceeded(spawns.size, limits.maxSpawnsPerTransition))
          else Nil

        // FiberPolicy dial `maxSpawnFanout`: a per-fiber cap STRICTER than (or equal to) the engine-default
        // maxSpawnsPerTransition. Same fail-closed abort path. Distinct FailureReason (PolicyViolation) so an
        // observer/test can tell a policy breach from the engine bound.
        val fanoutErrors: List[FailureReason] =
          parent.definition.policy.flatMap(_.maxSpawnFanout) match {
            case Some(cap) if spawns.size > cap =>
              List(
                FailureReason.PolicyViolation("maxSpawnFanout", s"transition emitted ${spawns.size} spawns (max: $cap)")
              )
            case _ => Nil
          }

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

        val allErrors = countErrors ++ fanoutErrors ++ duplicateErrors ++ collisionErrors

        NonEmptyList.fromList(allErrors) match {
          case Some(errors) => Validated.invalid(errors)
          case None         => Validated.validNel(SpawnPlan(spawns))
        }
      }
    }
}
