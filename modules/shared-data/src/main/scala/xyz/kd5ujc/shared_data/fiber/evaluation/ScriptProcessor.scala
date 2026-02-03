package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, MapValue, NullValue}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema._
import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.shared_data.syntax.all._

object ScriptProcessor {

  def extractStateAndResult[F[_]: Async](
    evaluationResult: JsonLogicValue
  ): F[(Option[JsonLogicValue], JsonLogicValue)] =
    evaluationResult match {
      case MapValue(m) if m.contains(ReservedKeys.ORACLE_STATE) && m.contains(ReservedKeys.ORACLE_RESULT) =>
        (m.get(ReservedKeys.ORACLE_STATE), m.getOrElse(ReservedKeys.ORACLE_RESULT, NullValue)).pure[F]

      case MapValue(m) if m.contains(ReservedKeys.ORACLE_STATE) =>
        (m.get(ReservedKeys.ORACLE_STATE), evaluationResult).pure[F]

      case MapValue(m) if m.contains(ReservedKeys.ORACLE_RESULT) =>
        (
          Option.empty[JsonLogicValue],
          m.getOrElse(ReservedKeys.ORACLE_RESULT, NullValue)
        ).pure[F]

      case other =>
        (other.some, other).pure[F]
    }

  def createScript[F[_]: Async: SecurityProvider](
    current:        DataState[OnChain, CalculatedState],
    update:         Signed[Updates.CreateScript],
    currentOrdinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] = for {
    owners <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)

    stateDataHashOpt <- update.initialState.traverse[F, Hash](_.computeDigest)

    oracleRecord = Records.ScriptFiberRecord(
      fiberId = update.fiberId,
      creationOrdinal = currentOrdinal,
      latestUpdateOrdinal = currentOrdinal,
      scriptProgram = update.scriptProgram,
      stateData = update.initialState,
      stateDataHash = stateDataHashOpt,
      accessControl = update.accessControl,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = owners,
      status = FiberStatus.Active
    )

    result <- current.withRecord[F](update.fiberId, oracleRecord)
  } yield result

  def validateAccess[F[_]: Async](
    policy:     AccessControlPolicy,
    caller:     Address,
    resourceId: UUID,
    state:      CalculatedState
  ): F[Either[FailureReason, Unit]] = policy match {
    case AccessControlPolicy.Public =>
      ().asRight[FailureReason].pure[F]

    case AccessControlPolicy.Whitelist(addresses) if addresses.contains(caller) =>
      ().asRight[FailureReason].pure[F]

    case AccessControlPolicy.Whitelist(addresses) =>
      (FailureReason.AccessDenied(
        caller = caller.show,
        resourceId = resourceId,
        policyType = "Whitelist",
        details = s"Allowed addresses: ${addresses.map(_.show).mkString(", ")}".some
      ): FailureReason).asLeft[Unit].pure[F]

    case AccessControlPolicy.FiberOwned(ownerFiberId) =>
      state.getFiber(ownerFiberId) match {
        case Some(ownerFiber) if ownerFiber.owners.contains(caller) =>
          ().asRight[FailureReason].pure[F]
        case Some(ownerFiber) =>
          (FailureReason.AccessDenied(
            caller = caller.show,
            resourceId = resourceId,
            policyType = "FiberOwned",
            details =
              s"Caller is not an owner of fiber $ownerFiberId (owners: ${ownerFiber.owners.map(_.show).mkString(", ")})".some
          ): FailureReason).asLeft[Unit].pure[F]
        case None =>
          (FailureReason.AccessDenied(
            caller = caller.show,
            resourceId = resourceId,
            policyType = "FiberOwned",
            details = s"Owner fiber $ownerFiberId not found".some
          ): FailureReason).asLeft[Unit].pure[F]
      }
  }
}
