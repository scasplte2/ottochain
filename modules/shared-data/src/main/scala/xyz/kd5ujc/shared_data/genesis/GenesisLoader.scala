package xyz.kd5ujc.shared_data.genesis

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState

import xyz.kd5ujc.schema.{CalculatedState, GenesisData, OnChain}

import fs2.io.file.{Files, Path}
import io.circe.parser.decode
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Loads the metagraph genesis `DataState` from a configured file path.
 *
 *   - Absent path (`None`)  ⇒ the empty genesis (`OnChain.genesis` / `CalculatedState.genesis`). This is the
 *     normal default and is NOT an error.
 *   - Present path (`Some`) ⇒ read the file and decode it as a [[GenesisData]] JSON document, then expand to a
 *     `DataState`. A present-but-unreadable or unparseable path FAILS LOUDLY (raised error): a misconfigured
 *     genesis path is an operator mistake, not a reason to silently boot the empty chain.
 */
object GenesisLoader {

  private def emptyGenesis: DataState[OnChain, CalculatedState] =
    DataState(OnChain.genesis, CalculatedState.genesis)

  def load[F[_]: Async: Files](path: Option[String]): F[DataState[OnChain, CalculatedState]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](GenesisLoader.getClass)

    path match {
      case None =>
        logger.info("No genesis state path configured; booting from the empty genesis.").as(emptyGenesis)

      case Some(p) =>
        for {
          _       <- logger.info(s"Loading genesis state from configured path: $p")
          content <- Files[F].readUtf8(Path(p)).compile.string
          ds <- decode[GenesisData](content) match {
            case Right(genesisData) => genesisData.toDataState.pure[F]
            case Left(err) =>
              Async[F].raiseError[DataState[OnChain, CalculatedState]](
                new IllegalStateException(s"Failed to decode genesis state from '$p': ${err.getMessage}", err)
              )
          }
          _ <- logger.info(s"Loaded genesis state from '$p'.")
        } yield ds
    }
  }
}
