package xyz.kd5ujc.shared_data.lifecycle.combine

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.fiber.evaluation.ScriptProcessor
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner operations for scripts.
 *
 * Handles creation and invocation of scripts.
 * Uses the syntax extensions for atomic state updates.
 *
 * @param current The current DataState to operate on
 * @param ctx     The L0NodeContext for accessing snapshot ordinals
 */
class ScriptCombiner[F[_]: Async: SecurityProvider](
  current:         DataState[OnChain, CalculatedState],
  ctx:             L0NodeContext[F],
  executionLimits: ExecutionLimits
) {

  /**
   * Creates a new script from the update, resolving an optional registry binding.
   */
  def createScript(
    update: Signed[Updates.CreateScript]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal
    binding        <- resolveScriptBinding(update.schemaRef, update.scriptProgram)
    result         <- ScriptProcessor.createScript(current, update, currentOrdinal, binding)
  } yield result

  /**
   * Invokes a script method.
   *
   * Delegates to FiberOrchestrator for consistent gas metering and
   * unified processing semantics with state machine transitions.
   */
  def invokeScript(
    update: Signed[Updates.InvokeScript]
  ): CombineResult[F] = for {
    currentOrdinal   <- ctx.getCurrentOrdinal
    lastSnapshotHash <- ctx.getLastSnapshotHash
    epochProgress    <- ctx.getEpochProgress

    // Verify script exists and sequence number matches before processing
    scriptRecord <- current.calculated.scripts
      .get(update.fiberId)
      .fold(
        Async[F].raiseError[Records.ScriptFiberRecord](
          CombineRejected(s"Script ${update.fiberId} not found")
        )
      )(_.pure[F])

    // Defense-in-depth: reject stale sequence numbers
    _ <- Async[F]
      .raiseError(
        CombineRejected(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${scriptRecord.sequenceNumber}"
        )
      )
      .whenA(scriptRecord.sequenceNumber =!= update.targetSequenceNumber)

    caller <- update.proofs.toList.headOption
      .fold(Async[F].raiseError[Address](CombineRejected("No proof provided")))(
        _.id.toAddress
      )

    // Delegate to FiberOrchestrator for consistent gas metering
    orchestrator = FiberEngine.make[F](
      calculatedState = current.calculated,
      ordinal = currentOrdinal,
      limits = executionLimits,
      lastSnapshotHash = lastSnapshotHash,
      epochProgress = epochProgress
    )

    input = FiberInput.MethodCall(
      method = update.method,
      args = update.args,
      caller = caller
    )

    outcome <- orchestrator.process(update.fiberId, input, update.proofs.toList)

    newState <- outcome match {
      case TransactionResult.Committed(_, updatedScripts, logEntries, _, _, _) =>
        updatedScripts.get(update.fiberId) match {
          case Some(updatedScript) =>
            current.withRecord[F](update.fiberId, updatedScript).map(_.appendLogs(logEntries))

          case None =>
            Async[F].raiseError(CombineRejected(s"Script ${update.fiberId} not found in orchestrator result"))
        }

      case TransactionResult.Aborted(reason, _, _) =>
        Async[F].raiseError(CombineRejected(s"Script invocation failed: ${reason.toMessage}"))
    }
  } yield newState

  /**
   * Upgrades an existing script fiber to a different registered version of the SAME package: verifies the
   * new program's hash against the target version (verified re-bind), applies the optional migration (a
   * JSON-Logic transform of the prior stateData) via the engine's metered evaluator, re-pins the binding,
   * and advances the sequence number.
   */
  def upgradeScript(
    update: Signed[Updates.UpgradeScript]
  ): CombineResult[F] = for {
    currentOrdinal   <- ctx.getCurrentOrdinal
    lastSnapshotHash <- ctx.getLastSnapshotHash
    epochProgress    <- ctx.getEpochProgress

    scriptRecord <- current.calculated.scripts
      .get(update.fiberId)
      .fold(
        Async[F].raiseError[Records.ScriptFiberRecord](
          CombineRejected(s"Script ${update.fiberId} not found")
        )
      )(_.pure[F])

    _ <- Async[F]
      .raiseError(
        CombineRejected(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${scriptRecord.sequenceNumber}"
        )
      )
      .whenA(scriptRecord.sequenceNumber =!= update.targetSequenceNumber)

    // Must currently be bound, and the upgrade must target the SAME package.
    _ <- scriptRecord.schemaBinding match {
      case Some(b) if b.name === update.targetRef.name => Async[F].unit
      case Some(b) =>
        Async[F].raiseError[Unit](
          CombineRejected(
            s"cannot upgrade ${b.name.render} script to a different package ${update.targetRef.name.render}"
          )
        )
      case None =>
        Async[F].raiseError[Unit](CombineRejected(s"script ${update.fiberId} has no binding to upgrade"))
    }

    // Resolve the target version and verify the new program's hash.
    maybeBinding <- resolveScriptBinding(Some(update.targetRef), update.newProgram)
    newBinding <- maybeBinding.fold(
      Async[F].raiseError[SchemaBinding](
        CombineRejected(s"upgrade target ${update.targetRef.name.render} did not resolve")
      )
    )(_.pure[F])

    // Monotonic upgrade: version may only ADVANCE.
    _ <- scriptRecord.schemaBinding match {
      case Some(b) if SemVer.ordering.gt(newBinding.version, b.version) => Async[F].unit
      case Some(b) =>
        Async[F].raiseError[Unit](
          CombineRejected(
            s"cannot downgrade ${b.name.render} script from ${b.version.render} to ${newBinding.version.render}"
          )
        )
      case None => Async[F].unit
    }

    // Apply optional migration through the metered evaluator.
    orchestrator = FiberEngine.make[F](
      calculatedState = current.calculated,
      ordinal = currentOrdinal,
      limits = executionLimits,
      lastSnapshotHash = lastSnapshotHash,
      epochProgress = epochProgress
    )

    outcome <- orchestrator.migrateScript(update.fiberId, update.newProgram, newBinding, update.migration)

    newState <- outcome match {
      case TransactionResult.Committed(_, updatedScripts, logEntries, _, _, _) =>
        updatedScripts.get(update.fiberId) match {
          case Some(updated) => current.withRecord[F](update.fiberId, updated).map(_.appendLogs(logEntries))
          case None =>
            Async[F].raiseError(CombineRejected(s"Script ${update.fiberId} not in orchestrator result"))
        }
      case TransactionResult.Aborted(reason, gasUsed, _) =>
        Async[F].raiseError(
          CombineRejected(s"Script upgrade failed (gas=$gasUsed): ${reason.toMessage}")
        )
    }
  } yield newState

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /**
   * Resolve an optional schema reference against the current registry for a SCRIPT fiber. Mirrors
   * FiberCombiner.resolveBinding but hashes the scriptProgram instead of a StateMachineDefinition.
   * Aborts if the name/version doesn't resolve or the program hash mismatches the registered logicHash.
   */
  private def resolveScriptBinding(
    ref:     Option[SchemaRef],
    program: JsonLogicExpression
  ): F[Option[SchemaBinding]] =
    ref match {
      case None => none[SchemaBinding].pure[F]
      case Some(SchemaRef(name, versionReq)) =>
        current.calculated.registry
          .get(name)
          .map(_.target)
          .collect { case RegistryTarget.SchemaPackage(l) => l } match {
          case None =>
            Async[F].raiseError[Option[SchemaBinding]](
              CombineRejected(s"schemaRef refers to unknown registry name ${name.render}")
            )
          case Some(lineage) =>
            lineage
              .resolve(versionReq)
              .fold(
                e =>
                  Async[F].raiseError[Option[SchemaBinding]](
                    CombineRejected(s"schemaRef unresolvable for ${name.render}: $e")
                  ),
                rv =>
                  program.computeDigest.flatMap { digest =>
                    if (digest === rv.logicHash)
                      SchemaBinding(name, rv.version, rv.schemaHash, rv.logicHash).some.pure[F]
                    else
                      Async[F].raiseError[Option[SchemaBinding]](
                        CombineRejected(
                          s"schemaRef logic mismatch for ${name.render}@${rv.version.render}: program hash " +
                          s"${digest.value} != registered logicHash ${rv.logicHash.value}"
                        )
                      )
                  }
              )
        }
    }

}

object ScriptCombiner {

  /**
   * Creates a new ScriptCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider](
    current:         DataState[OnChain, CalculatedState],
    ctx:             L0NodeContext[F],
    executionLimits: ExecutionLimits
  ): ScriptCombiner[F] =
    new ScriptCombiner[F](current, ctx, executionLimits)
}
