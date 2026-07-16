package xyz.kd5ujc.shared_data.syntax

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps

import xyz.kd5ujc.schema.fiber.FiberLogEntry
import xyz.kd5ujc.schema.registry.{RegistryEntry, RegistryName}
import xyz.kd5ujc.schema.{CalculatedState, FiberCommit, OnChain, Records}

import monocle.Monocle.toAppliedFocusOps

/**
 * Extension methods for DataState to simplify state updates.
 *
 * These operations ensure that OnChain (the per-batch `touched*` delta), the cumulative
 * CalculatedState commit maps, and the CalculatedState records are updated atomically — the
 * same fold writes all three from the same computed hash, so delta and cumulative state cannot
 * diverge (onchain-incrementals RFC §3.1). RecordHash and stateDataHash are computed
 * internally — callers only provide the record.
 */
trait DataStateOps {

  implicit class DataStateSyntax(private val state: DataState[OnChain, CalculatedState]) {

    /**
     * Update a single fiber or script record with automatic hash computation.
     *
     * Computes RecordHash from the full record and extracts stateDataHash from
     * the record's field. Routes to the correct CalculatedState field based on type.
     *
     * @param id     The fiber/script CID
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
              .focus(_.onChain.touchedFiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.fiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.stateMachines)
              .modify(_.updated(id, sm))
          }
        case script: Records.ScriptFiberRecord =>
          script.computeDigest.map { recordHash =>
            val commit = FiberCommit(recordHash, script.stateDataHash, script.sequenceNumber)
            state
              .focus(_.onChain.touchedFiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.fiberCommits)
              .modify(_.updated(id, commit))
              .focus(_.calculated.scripts)
              .modify(_.updated(id, script))
          }
      }

    /**
     * Batch update for multiple records of mixed types.
     *
     * Separates into state machines and scripts, computes hashes for each,
     * and applies all updates atomically.
     *
     * @param records Map of CIDs to updated records
     * @return Updated DataState with all entities applied
     */
    def withRecords[F[_]: Async](
      records: Map[UUID, Records.FiberRecord]
    ): F[DataState[OnChain, CalculatedState]] = {
      val sms = records.collect { case (id, sm: Records.StateMachineFiberRecord) => id -> sm }
      val scripts = records.collect { case (id, o: Records.ScriptFiberRecord) => id -> o }

      for {
        smHashes <- sms.toList.traverse { case (id, sm) =>
          sm.computeDigest.map(recordHash => id -> FiberCommit(recordHash, Some(sm.stateDataHash), sm.sequenceNumber))
        }
        scriptHashes <- scripts.toList.traverse { case (id, o) =>
          o.computeDigest.map(recordHash => id -> FiberCommit(recordHash, o.stateDataHash, o.sequenceNumber))
        }
      } yield state
        .focus(_.onChain.touchedFiberCommits)
        .modify(_ ++ smHashes.toMap ++ scriptHashes.toMap)
        .focus(_.calculated.fiberCommits)
        .modify(_ ++ smHashes.toMap ++ scriptHashes.toMap)
        .focus(_.calculated.stateMachines)
        .modify(_ ++ sms)
        .focus(_.calculated.scripts)
        .modify(_ ++ scripts)
    }

    /**
     * Batch update for state machines and scripts provided as separate typed maps.
     *
     * @param fibers  Map of fiber IDs to updated fiber records
     * @param scripts Map of script IDs to updated script records
     * @return Updated DataState with all entities applied
     */
    def withFibersAndScripts[F[_]: Async](
      fibers:  Map[UUID, Records.StateMachineFiberRecord],
      scripts: Map[UUID, Records.ScriptFiberRecord]
    ): F[DataState[OnChain, CalculatedState]] =
      withRecords(fibers ++ scripts)

    /**
     * Commit a registry entry atomically: store the entry in CalculatedState.registry, its hash in the
     * cumulative CalculatedState.registryCommits, and the same hash in the per-batch
     * OnChain.touchedRegistryCommits delta. The chain commits only the entry's hash + metadata, never
     * schema/definition bytes.
     */
    def withRegistryEntry[F[_]: Async](
      name:  RegistryName,
      entry: RegistryEntry
    ): F[DataState[OnChain, CalculatedState]] =
      entry.computeDigest.map { entryHash =>
        state
          .focus(_.onChain.touchedRegistryCommits)
          .modify(_.updated(name, entryHash))
          .focus(_.calculated.registryCommits)
          .modify(_.updated(name, entryHash))
          .focus(_.calculated.registry)
          .modify(_.updated(name, entry))
      }

    /**
     * Commit a fiber-alias entry (#29): the forward entry (name -> InstanceAlias) plus the canonical
     * reverse record (targetFiberId -> name) used to render human-readable audit trails.
     */
    def withAlias[F[_]: Async](
      name:          RegistryName,
      entry:         RegistryEntry,
      targetFiberId: UUID
    ): F[DataState[OnChain, CalculatedState]] =
      withRegistryEntry[F](name, entry).map(
        _.focus(_.calculated.reverseNames).modify(_.updated(targetFiberId, name))
      )

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
