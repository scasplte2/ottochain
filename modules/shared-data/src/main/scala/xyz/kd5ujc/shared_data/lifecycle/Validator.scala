package xyz.kd5ujc.shared_data.lifecycle

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.metagraph_sdk.lifecycle.{CheckpointService, ValidationService}
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.syntax.all.L1ContextOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.validate.{FiberValidator, ScriptValidator}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Entry point for creating a ValidationService.
 *
 * This object provides backward compatibility by delegating to the
 * modular validation package. New code should use ValidationService directly.
 *
 * == Validation Layers ==
 *
 * - '''L1 (Data-L1)''': Structural validations at API ingestion
 *   - Available state: OnChain only
 *   - Called via: validateUpdate
 *   - Examples: CID uniqueness, definition structure, payload size
 *
 * - '''L0 (Metagraph-L0)''': Contextual validations at consensus
 *   - Available state: OnChain + CalculatedState + Proofs
 *   - Called via: validateSignedUpdate
 *   - Examples: Ownership, status checks, access control
 *
 * Runtime checks (state size, gas limits) are in the fiber engine module.
 *
 * @see [[validate.ValidateService]] for the validation implementation
 * @see [[xyz.kd5ujc.shared_data.fiber.engine.RuntimeChecks]] for execution-time checks
 */
object Validator {

  /**
   * Creates a ValidationService instance.
   *
   * @return A ValidationService that validates OttochainMessage updates
   */
  def make[F[_]: Async: Parallel: SecurityProvider]
    : F[ValidationService[F, OttochainMessage, OnChain, CalculatedState]] =
    CheckpointService.make[F, OnChain](OnChain.genesis).map { checkpointService =>
      new ValidationService[F, OttochainMessage, OnChain, CalculatedState] {

        private val logger: SelfAwareStructuredLogger[F] =
          Slf4jLogger.getLoggerFromClass(Validator.getClass)

        /**
         * L1 Validation - Structural checks at Data-L1 ingestion.
         *
         * Called by the Data-L1 node when updates are received via the API.
         * Uses cached on-chain state for efficient batch processing.
         */
        override def validateUpdate(
          update: OttochainMessage
        )(implicit ctx: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
          withOnChainCache(ctx) { checkpoint =>
            val fiberL1 = new FiberValidator.L1Validator[F](checkpoint.state)
            val oracleL1 = new ScriptValidator.L1Validator[F](checkpoint.state)

            val updateName = update.getClass.getSimpleName
            val fiberId = update match {
              case u: CreateStateMachine     => u.fiberId.toString
              case u: TransitionStateMachine => u.fiberId.toString
              case u: ArchiveStateMachine    => u.fiberId.toString
              case u: CreateScript           => u.fiberId.toString
              case u: InvokeScript           => u.fiberId.toString
            }
            val cids = checkpoint.state.fiberCommits.keys.map(_.toString.take(8)).mkString(", ")

            for {
              _ <- logger.info(
                s"[DL1-validate] $updateName fiberId=${fiberId.take(8)}... " +
                s"cacheOrdinal=${checkpoint.ordinal} " +
                s"cachedCids=[$cids]"
              )
              result <- update match {
                case u: CreateStateMachine     => fiberL1.createFiber(u)
                case u: TransitionStateMachine => fiberL1.processEvent(u)
                case u: ArchiveStateMachine    => fiberL1.archiveFiber(u)
                case u: CreateScript           => oracleL1.createOracle(u)
                case u: InvokeScript           => oracleL1.invokeOracle(u)
              }
              _ <- logger.info(
                s"[DL1-validate] $updateName fiberId=${fiberId.take(8)}... " +
                s"result=${result.fold(errs => s"INVALID: ${errs.toList.map(_.message).mkString("; ")}", _ => "VALID")}"
              )
            } yield result
          }

        /**
         * L0 Validation - Full validation at Metagraph-L0 consensus.
         *
         * Called by the Metagraph-L0 node during block processing.
         * Has access to full state (OnChain + CalculatedState) and signature proofs.
         *
         * Runs both L1 (structural) and L0 (contextual) validations to ensure
         * complete validation even if L1 checks were bypassed.
         */
        override def validateSignedUpdate(
          current:      DataState[OnChain, CalculatedState],
          signedUpdate: Signed[OttochainMessage]
        )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] = {
          val fiberCombined = new FiberValidator.CombinedValidator[F](current, signedUpdate.proofs)
          val oracleCombined = new ScriptValidator.CombinedValidator[F](current, signedUpdate.proofs)

          signedUpdate.value match {
            case u: CreateStateMachine     => fiberCombined.createFiber(u)
            case u: TransitionStateMachine => fiberCombined.processEvent(u)
            case u: ArchiveStateMachine    => fiberCombined.archiveFiber(u)
            case u: CreateScript           => oracleCombined.createOracle(u)
            case u: InvokeScript           => oracleCombined.invokeOracle(u)
          }
        }

        /**
         * Executes validation with cached on-chain state.
         *
         * The checkpoint is refreshed when a new snapshot is available,
         * avoiding redundant state fetches during batch processing.
         */
        private def withOnChainCache(context: L1NodeContext[F])(
          f: Checkpoint[OnChain] => F[DataApplicationValidationErrorOr[Unit]]
        ): F[DataApplicationValidationErrorOr[Unit]] =
          checkpointService
            .evalModify[DataApplicationValidationError] { checkpoint =>
              context.getLatestCurrencySnapshot.flatMap {
                case Right(snapshot) if snapshot.ordinal > checkpoint.ordinal =>
                  logger.info(
                    s"[DL1-cache] REFRESHING: snapshotOrdinal=${snapshot.ordinal} > cacheOrdinal=${checkpoint.ordinal}"
                  ) *>
                  context.getOnChainState[OnChain].flatMap {
                    case Right(newState) =>
                      val cids = newState.fiberCommits.keys.map(_.toString.take(8)).mkString(", ")
                      logger
                        .info(
                          s"[DL1-cache] REFRESHED: ordinal=${snapshot.ordinal} fiberCommits=${newState.fiberCommits.size} cids=[$cids]"
                        )
                        .as(Checkpoint(snapshot.ordinal, newState).asRight[DataApplicationValidationError])
                    case Left(err) =>
                      logger.warn(s"[DL1-cache] REFRESH FAILED: $err").as(err.asLeft[Checkpoint[OnChain]])
                  }
                case Right(snapshot) =>
                  logger.debug(
                    s"[DL1-cache] NO REFRESH: snapshotOrdinal=${snapshot.ordinal} == cacheOrdinal=${checkpoint.ordinal}"
                  ) *> checkpoint.asRight[DataApplicationValidationError].pure[F]
                case Left(err) =>
                  logger.warn(s"[DL1-cache] SNAPSHOT ERROR: $err").as(err.asLeft[Checkpoint[OnChain]])
              }
            }
            .flatMap(_.fold(_.invalidNec[Unit].pure[F], f))
      }
    }
}
