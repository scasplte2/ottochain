package xyz.kd5ujc.shared_data.lifecycle

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FiberLogEntry}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.combine.{
  AssetCombiner,
  CombineRejected,
  FiberCombiner,
  RegistryCombiner,
  ScriptCombiner
}
import xyz.kd5ujc.shared_data.lifecycle.validate.Limits
import xyz.kd5ujc.shared_data.syntax.all._

import org.typelevel.log4cats.slf4j.Slf4jLogger

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
 *   - CreateScript: Initialize new script with script
 *   - InvokeScript: Execute script method
 *
 * @see [[combine.FiberCombiner]] for fiber operations
 * @see [[combine.ScriptCombiner]] for script operations
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
        val scriptCombiner = ScriptCombiner[F](previous, ctx, executionLimits)
        val registryCombiner = RegistryCombiner[F](previous, ctx, Limits.MaxRegistryBundleBytes)
        val assetCombiner = AssetCombiner[F](previous, ctx, Limits.MaxRegistryBundleBytes)

        val dispatched: F[DataState[OnChain, CalculatedState]] = update.value match {
          case u: Updates.CreateStateMachine     => fiberCombiner.createStateMachineFiber(Signed(u, update.proofs))
          case u: Updates.TransitionStateMachine => fiberCombiner.processFiberEvent(Signed(u, update.proofs))
          case u: Updates.ArchiveStateMachine    => fiberCombiner.archiveFiber(Signed(u, update.proofs))
          case u: Updates.UpgradeFiber           => fiberCombiner.upgradeFiber(Signed(u, update.proofs))
          case u: Updates.CreateScript           => scriptCombiner.createScript(Signed(u, update.proofs))
          case u: Updates.InvokeScript           => scriptCombiner.invokeScript(Signed(u, update.proofs))
          case u: Updates.UpgradeScript          => scriptCombiner.upgradeScript(Signed(u, update.proofs))
          case u: Updates.PublishMachineVersion  => registryCombiner.publishMachineVersion(Signed(u, update.proofs))
          case u: Updates.PublishScriptVersion   => registryCombiner.publishScriptVersion(Signed(u, update.proofs))
          case u: Updates.SetVersionStatus       => registryCombiner.setVersionStatus(Signed(u, update.proofs))
          case u: Updates.RegisterAlias          => registryCombiner.registerAlias(Signed(u, update.proofs))
          case u: Updates.CreateAssetPolicy      => assetCombiner.createAssetPolicy(Signed(u, update.proofs))
          case u: Updates.MintAsset              => assetCombiner.mintAsset(Signed(u, update.proofs))
          case u: Updates.ApplyMorphism          => assetCombiner.applyMorphism(Signed(u, update.proofs))
          case u: Updates.AuthorizeCompose       => assetCombiner.authorizeCompose(Signed(u, update.proofs))
        }

        // Classify & accumulate (no short-circuit): a deterministic business rejection (CombineRejected) must
        // NOT abort the snapshot's combine. Record a RejectionReceipt and continue with the UNMUTATED
        // `previous` state. Any other failure (non-deterministic / infrastructure) propagates and aborts — by
        // design, so a transient local error never becomes divergent committed state across nodes.
        dispatched.recoverWith { case CombineRejected(reason) =>
          for {
            _ <- Slf4jLogger
              .getLogger[F]
              .warn(
                s"[combine-reject] ${update.value.getClass.getSimpleName} fiberId=${update.value.fiberId} reason=$reason"
              )
            ordinal    <- ctx.getCurrentOrdinal
            updateHash <- update.value.computeDigest
          } yield {
            val fid = update.value.fiberId
            val targetSeq: Option[Long] = update.value match {
              case u: Updates.TransitionStateMachine => Some(u.targetSequenceNumber.value.value)
              case u: Updates.ArchiveStateMachine    => Some(u.targetSequenceNumber.value.value)
              case u: Updates.UpgradeFiber           => Some(u.targetSequenceNumber.value.value)
              case u: Updates.UpgradeScript          => Some(u.targetSequenceNumber.value.value)
              case u: Updates.InvokeScript           => Some(u.targetSequenceNumber.value.value)
              case u: Updates.ApplyMorphism          => Some(u.targetSequenceNumber.value.value)
              case u: Updates.AuthorizeCompose       => Some(u.targetSequenceNumber.value.value)
              case _                                 => None
            }
            val actualSeq: Option[Long] =
              previous.calculated.stateMachines
                .get(fid)
                .map(_.sequenceNumber.value.value)
                .orElse(previous.calculated.scripts.get(fid).map(_.sequenceNumber.value.value))
            previous.appendLogs(
              List(
                FiberLogEntry.RejectionReceipt(
                  fiberId = fid,
                  ordinal = ordinal,
                  updateType = update.value.getClass.getSimpleName,
                  reason = reason,
                  targetSequenceNumber = targetSeq,
                  actualSequenceNumber = actualSeq,
                  updateHash = updateHash.value
                )
              )
            )
          }
        }
      }
    }
}
