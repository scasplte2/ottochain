package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.registry.RegistryName

import org.http4s.HttpRoutes
import org.http4s.server.Router

/**
 * Registry endpoints — schema-package + alias lifecycle (discovery / e2e assertions): all entries,
 * reverse fiber→name lookup, and single entry by `labels.tld` name.
 */
class ML0RegistryRoutes[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
) extends MetagraphPublicRoutes[F] {

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // All registry entries: name -> RegistryEntry (target = schema-package version lineage, or alias).
    case GET -> Root / "registry" =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.registry.asRight[DataApplicationValidationError]
      }.toResponse

    // Reverse record (#29): fiber UUID -> its canonical registered name. Declared before the
    // by-name route; arity differs so there is no ambiguity, but keep the specific path first.
    case GET -> Root / "registry" / "reverse" / UUIDVar(fiberId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.reverseNames.get(fiberId).asRight[DataApplicationValidationError]
      }.toResponse

    // A single entry by full `labels.tld` name (e.g. `counter.package`, `my-counter.machine`).
    case GET -> Root / "registry" / nameStr =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        RegistryName
          .from(nameStr)
          .toOption
          .flatMap(state.registry.get)
          .asRight[DataApplicationValidationError]
      }.toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
