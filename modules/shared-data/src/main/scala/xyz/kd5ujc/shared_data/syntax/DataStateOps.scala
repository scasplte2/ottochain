package xyz.kd5ujc.shared_data.syntax

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps

import xyz.kd5ujc.schema.fiber.FiberLogEntry
import xyz.kd5ujc.schema.{CalculatedState, FiberCommit, OnChain, Records}

import monocle.Monocle.toAppliedFocusOps

/**
 * Extension methods for DataState to simplify state updates.
 *
 * These operations ensure that both OnChain (hashes) and CalculatedState (records)
 * are updated atomically. RecordHash and stateDataHash are computed internally —
 * callers only provide the record.
 */
trait DataStateOps {

  implicit class DataStateSyntax(private val state: DataState[OnChain, CalculatedState]) {

    /**
     * Update a single fiber or oracle record with automatic hash computation.
     *
     * Computes RecordHash from the full record and extracts stateDataHash from
     * the record's field. Routes to the correct CalculatedState field based on type.
     *
     * @param id     The fiber/oracle CID
     * @param record The updated record (StateMachineFiberRecord or ScriptFiberRecord)
     * @return Updated DataState with both OnChain and CalculatedState modified
     */
    def withRecord[F[_]: Async](
      id:     UUID,
      record: Records.FiberRecord
    ): F[DataState[OnChain, CalculatedState]] =
      record match {
        case sm: Records.StateMachineFiberRecord =>
          sm.computeDigest.map { recordHash =>
            val commit = FiberCommit(recordHash, Some(sm.stateDataHash), sm.sequenceNumber)
            state
              .focus(_.onChain.fiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.stateMachines)
              .modify(_.updated(id, sm))
          }
        case oracle: Records.ScriptFiberRecord =>
          oracle.computeDigest.map { recordHash =>
            val commit = FiberCommit(recordHash, oracle.stateDataHash, oracle.sequenceNumber)
            state
              .focus(_.onChain.fiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.scripts)
              .modify(_.updated(id, oracle))
          }
      }

    /**
     * Batch update for multiple records of mixed types.
     *
     * Separates into state machines and oracles, computes hashes for each,
     * and applies all updates atomically.
     *
     * @param records Map of CIDs to updated records
     * @return Updated DataState with all entities applied
     */
    def withRecords[F[_]: Async](
      records: Map[UUID, Records.FiberRecord]
    ): F[DataState[OnChain, CalculatedState]] = {
      val sms = records.collect { case (id, sm: Records.StateMachineFiberRecord) => id -> sm }
      val oracles = records.collect { case (id, o: Records.ScriptFiberRecord) => id -> o }

      for {
        smHashes <- sms.toList.traverse { case (id, sm) =>
          sm.computeDigest.map(recordHash => id -> FiberCommit(recordHash, Some(sm.stateDataHash), sm.sequenceNumber))
        }
        oracleHashes <- oracles.toList.traverse { case (id, o) =>
          o.computeDigest.map(recordHash => id -> FiberCommit(recordHash, o.stateDataHash, o.sequenceNumber))
        }
      } yield state
        .focus(_.onChain.fiberCommits)
        .modify(_ ++ smHashes.toMap ++ oracleHashes.toMap)
        .focus(_.calculated.stateMachines)
        .modify(_ ++ sms)
        .focus(_.calculated.scripts)
        .modify(_ ++ oracles)
    }

    /**
     * Batch update for state machines and oracles provided as separate typed maps.
     *
     * @param fibers  Map of fiber IDs to updated fiber records
     * @param oracles Map of oracle IDs to updated oracle records
     * @return Updated DataState with all entities applied
     */
    def withFibersAndOracles[F[_]: Async](
      fibers:  Map[UUID, Records.StateMachineFiberRecord],
      oracles: Map[UUID, Records.ScriptFiberRecord]
    ): F[DataState[OnChain, CalculatedState]] =
      withRecords(fibers ++ oracles)

    /**
     * Append log entries to OnChain.latestLogs, grouping by fiberId.
     *
     * Entries are merged into the existing map: new entries for a given fiber ID
     * are appended to any already-present entries for that ID.
     */
    def appendLogs(entries: List[FiberLogEntry]): DataState[OnChain, CalculatedState] = {
      val grouped = entries.groupBy(_.fiberId)
      state.focus(_.onChain.latestLogs).modify { current =>
        grouped.foldLeft(current) { case (acc, (fid, logs)) =>
          acc.updated(fid, acc.getOrElse(fid, List.empty) ++ logs)
        }
      }
    }
  }
}

object DataStateOps extends DataStateOps
