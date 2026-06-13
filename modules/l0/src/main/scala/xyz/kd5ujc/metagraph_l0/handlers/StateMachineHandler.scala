package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import scala.collection.immutable.SortedMap

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber.{AuditRenderer, FiberStatus}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

/** State-machine fiber logic: list (optionally by status), single record, event log, audit render. */
class StateMachineHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
)(implicit
  context: L0NodeContext[F]
) {

  def list(
    status: Option[FiberStatus]
  ): F[Either[DataApplicationValidationError, SortedMap[UUID, Records.StateMachineFiberRecord]]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      status
        .fold(state.stateMachines) { s =>
          state.stateMachines.filter { case (_, fiber) => fiber.status == s }
        }
        .asRight[DataApplicationValidationError]
    }

  def get(fiberId: UUID): F[Either[DataApplicationValidationError, Option[Records.StateMachineFiberRecord]]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.stateMachines.get(fiberId).asRight[DataApplicationValidationError]
    }

  def events(fiberId: UUID): F[Either[DataApplicationValidationError, List[EventReceipt]]] =
    context
      .getOnChainState[OnChain]
      .map(_.map { onChain =>
        onChain.latestLogs.getOrElse(fiberId, List.empty).collect { case r: EventReceipt => r }
      })

  def audit(fiberId: UUID) =
    checkpointService.get.flatMap { checkpoint =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          AuditRenderer.renderAll(
            onChain.latestLogs.getOrElse(fiberId, List.empty),
            checkpoint.state.reverseNames.toMap
          )
        })
    }
}
