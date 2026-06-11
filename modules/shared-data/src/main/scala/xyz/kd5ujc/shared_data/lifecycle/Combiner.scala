package xyz.kd5ujc.shared_data.lifecycle

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FiberLogEntry}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.combine.{CombineRejected, FiberCombiner, RegistryCombiner, ScriptCombiner}
import xyz.kd5ujc.shared_data.lifecycle.validate.Limits
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Entry point for creating a CombinerService.
 *
 * This object provides a thin facade that delegates to domain-specific
 * combiners in the [[combine]] package.
 *
 * == Combiner Architecture ==
 *
 * - '''FiberCombiner''': Handles state machine fiber operations
 *   - CreateStateMachineFiber: Initialize new fiber with definition
 *   - ProcessFiberEvent: Execute state transitions through orchestrator
 *   - ArchiveFiber: Mark fiber as archived
 *
 * - '''ScriptCombiner''': Handles script operations
 *   - CreateScript: Initialize new oracle with script
 *   - InvokeScript: Execute oracle method
 *
 * @see [[combine.FiberCombiner]] for fiber operations
 * @see [[combine.ScriptCombiner]] for oracle operations
 */
object Combiner {

  /**
   * Creates a CombinerService instance.
   *
   * @return A CombinerService that combines OttochainMessage updates into state
   */
  def make[F[_]: Async: SecurityProvider](
    executionLimits: ExecutionLimits = ExecutionLimits()
  ): CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {

      override def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] = {
        val fiberCombiner = FiberCombiner[F](previous, ctx, executionLimits)
        val oracleCombiner = ScriptCombiner[F](previous, ctx, executionLimits)
        val registryCombiner = RegistryCombiner[F](previous, ctx, Limits.MaxRegistryBundleBytes)

        val dispatched: F[DataState[OnChain, CalculatedState]] = update.value match {
          case u: Updates.CreateStateMachine     => fiberCombiner.createStateMachineFiber(Signed(u, update.proofs))
          case u: Updates.TransitionStateMachine => fiberCombiner.processFiberEvent(Signed(u, update.proofs))
          case u: Updates.ArchiveStateMachine    => fiberCombiner.archiveFiber(Signed(u, update.proofs))
          case u: Updates.UpgradeFiber           => fiberCombiner.upgradeFiber(Signed(u, update.proofs))
          case u: Updates.CreateScript           => oracleCombiner.createScript(Signed(u, update.proofs))
          case u: Updates.InvokeScript           => oracleCombiner.invokeScript(Signed(u, update.proofs))
          case u: Updates.PublishVersion         => registryCombiner.publishVersion(Signed(u, update.proofs))
          case u: Updates.SetVersionStatus       => registryCombiner.setVersionStatus(Signed(u, update.proofs))
          case u: Updates.RegisterAlias          => registryCombiner.registerAlias(Signed(u, update.proofs))
        }

        // Classify & accumulate (no short-circuit): a deterministic business rejection (CombineRejected) must
        // NOT abort the snapshot's combine. Record a RejectionReceipt and continue with the UNMUTATED
        // `previous` state. Any other failure (non-deterministic / infrastructure) propagates and aborts — by
        // design, so a transient local error never becomes divergent committed state across nodes.
        dispatched.recoverWith { case CombineRejected(reason) =>
          ctx.getCurrentOrdinal.map { ordinal =>
            previous.appendLogs(
              List(
                FiberLogEntry.RejectionReceipt(
                  fiberId = update.value.fiberId,
                  ordinal = ordinal,
                  updateType = update.value.getClass.getSimpleName,
                  reason = reason
                )
              )
            )
          }
        }
      }
    }
}
