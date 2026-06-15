package xyz.kd5ujc.shared_test

import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.{FeeTransaction, L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.schema.currency
import io.constellationnetwork.currency.schema.currency.DataApplicationPart
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.metagraph_sdk.crypto.smt.SparseMerkleRoot
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommittedBreadcrumb, CommittedOnChain, CommittedRoots}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import xyz.kd5ujc.schema.OnChain

import Generators.{genHashedCurrencyIncSnapshot, generateValueWithRetry}

object Mock {

  trait MockL0NodeContext[F[_]] extends L0NodeContext[F]

  object MockL0NodeContext {

    def make[F[_]: Sync]: F[MockL0NodeContext[F]] = {
      val currencySnapshot = generateValueWithRetry(genHashedCurrencyIncSnapshot).some

      Sync[F].delay(
        new MockL0NodeContext[F] {

          def getLastCurrencySnapshot: F[Option[Hashed[currency.CurrencyIncrementalSnapshot]]] =
            currencySnapshot.pure[F]

          def getCurrencySnapshot(
            ordinal: SnapshotOrdinal
          ): F[Option[Hashed[currency.CurrencyIncrementalSnapshot]]] = ???

          def getLastCurrencySnapshotCombined
            : F[Option[(Hashed[currency.CurrencyIncrementalSnapshot], currency.CurrencySnapshotInfo)]] = ???

          def securityProvider: SecurityProvider[F] = ???

          def getCurrencyId: F[CurrencyId] = ???

          def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = None

          def getLastSynchronizedAllowSpends
            : F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[swap.AllowSpend]]]]]] = ???

          def getLastSynchronizedTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[tokenLock.TokenLock]]]]] = ???

          def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]] = ???

          def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
            ???

          def getSnapshotFeeTransactions: F[Map[Hash, Signed[FeeTransaction]]] =
            currencySnapshot
              .flatMap(_.signed.value.feeTransactions)
              .fold(Map.empty[Hash, Signed[FeeTransaction]]) {
                _.map(sf => sf.value.dataUpdateRef -> sf).toMap
              }
              .pure[F]
        }
      )
    }
  }

  trait MockL1NodeContext[F[_]] extends L1NodeContext[F]

  object MockL1NodeContext {

    def make[F[_]: Sync](implicit sp: SecurityProvider[F]): F[MockL1NodeContext[F]] = {
      val baseSnapshot = generateValueWithRetry(genHashedCurrencyIncSnapshot)

      for {
        // ML0 commits CommittedOnChain[OnChain] (makeL0 wraps OnChain with the committed breadcrumb);
        // the DL1 validator decodes the wrapper and reads .inner, so the fixture serializes the same
        // shape. The breadcrumb value is irrelevant to the unwrap.
        onChainBytes <- CommittedOnChain(
          OnChain.genesis,
          CommittedBreadcrumb(SnapshotOrdinal.MinValue, CommittedRoots(Hash.empty, SparseMerkleRoot.empty))
        ).toBinary

        dataAppPart = DataApplicationPart(
          onChainState = onChainBytes,
          blocks = List.empty,
          calculatedStateProof = Hash.empty,
          updateHashes = None
        )

        snapshotWithData = baseSnapshot.signed.value.copy(dataApplication = dataAppPart.some)
        hashedSnapshot = Hashed(
          Signed(snapshotWithData, baseSnapshot.signed.proofs),
          baseSnapshot.hash,
          baseSnapshot.proofsHash
        )
      } yield new MockL1NodeContext[F] {

        def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] =
          none[Hashed[GlobalIncrementalSnapshot]].pure[F]

        def getLastCurrencySnapshot: F[Option[Hashed[currency.CurrencyIncrementalSnapshot]]] =
          hashedSnapshot.some.pure[F]

        def getLastCurrencySnapshotCombined
          : F[Option[(Hashed[currency.CurrencyIncrementalSnapshot], currency.CurrencySnapshotInfo)]] =
          none[(Hashed[currency.CurrencyIncrementalSnapshot], currency.CurrencySnapshotInfo)].pure[F]

        def securityProvider: SecurityProvider[F] = sp

        def getCurrencyId: F[CurrencyId] = ???
      }
    }
  }
}
