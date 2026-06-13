package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.registry.RegistryName

/** Registry logic: all entries, reverse fiber→name lookup, single entry by `labels.tld` name. */
class RegistryHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
) {

  def all =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.registry.asRight[DataApplicationValidationError]
    }

  def reverse(fiberId: UUID) =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.reverseNames.get(fiberId).asRight[DataApplicationValidationError]
    }

  def byName(nameStr: String) =
    checkpointService.get.map { case Checkpoint(_, state) =>
      RegistryName
        .from(nameStr)
        .toOption
        .flatMap(state.registry.get)
        .asRight[DataApplicationValidationError]
    }
}
