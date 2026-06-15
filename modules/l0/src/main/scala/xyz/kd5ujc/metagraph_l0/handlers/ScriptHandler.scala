package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.schema.fiber.FiberLogEntry.ScriptInvocation
import xyz.kd5ujc.schema.fiber.FiberStatus
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}

/**
 * Script-fiber logic backing the `/scripts` API surface:
 * list (optionally by status), single record, invocation log.
 */
class ScriptHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) {

  def list(
    status: Option[FiberStatus]
  ): F[Either[DataApplicationValidationError, SortedMap[UUID, Records.ScriptFiberRecord]]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      status
        .fold(state.scripts) { s =>
          state.scripts.filter { case (_, script) => script.status == s }
        }
        .asRight[DataApplicationValidationError]
    }

  def get(scriptId: UUID): F[Either[DataApplicationValidationError, Option[Records.ScriptFiberRecord]]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.scripts.get(scriptId).asRight[DataApplicationValidationError]
    }

  def invocations(scriptId: UUID): F[Either[DataApplicationValidationError, List[ScriptInvocation]]] =
    context
      .getOnChainState[OnChain]
      .map(_.map { onChain =>
        onChain.latestLogs.getOrElse(scriptId, List.empty).collect { case i: ScriptInvocation => i }
      })
}
