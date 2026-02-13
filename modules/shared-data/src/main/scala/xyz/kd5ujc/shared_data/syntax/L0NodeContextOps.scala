package xyz.kd5ujc.shared_data.syntax

import cats.effect.Sync
import cats.syntax.flatMap._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash

/**
 * Extension methods for L0NodeContext to simplify common operations.
 */
trait L0NodeContextOps {

  implicit class L0NodeContextSyntax[F[_]: Sync: SecurityProvider](private val ctx: L0NodeContext[F]) {

    /**
     * Get the current snapshot ordinal (next ordinal after latest snapshot).
     *
     * @return The current ordinal for use in state updates
     */
    def getCurrentOrdinal: F[SnapshotOrdinal] =
      ctx.getLastCurrencySnapshot.flatMap { latestSnapshot =>
        Sync[F].fromOption(
          latestSnapshot.map(_.signed.value.ordinal.next),
          new RuntimeException("Failed to retrieve latest currency snapshot!")
        )
      }

    /**
     * Get the hash of the last snapshot (for deterministic randomness).
     *
     * @return The last snapshot hash
     */
    def getLastSnapshotHash: F[Hash] =
      ctx.getLastCurrencySnapshot.flatMap { latestSnapshot =>
        Sync[F].fromOption(
          latestSnapshot.map(_.hash),
          new RuntimeException("Failed to retrieve latest currency snapshot!")
        )
      }

    /**
     * Get the current epoch progress.
     *
     * @return The epoch progress from the latest snapshot
     */
    def getEpochProgress: F[EpochProgress] =
      ctx.getLastCurrencySnapshot.flatMap { latestSnapshot =>
        Sync[F].fromOption(
          latestSnapshot.map(_.signed.value.epochProgress),
          new RuntimeException("Failed to retrieve latest currency snapshot!")
        )
      }
  }
}

object L0NodeContextOps extends L0NodeContextOps
