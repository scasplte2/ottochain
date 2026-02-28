package xyz.kd5ujc.shared_test

import java.io.InputStream
import java.security.{KeyPair, KeyStore, PrivateKey}

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

import xyz.kd5ujc.schema.Updates.OttochainMessage

import io.circe.syntax.EncoderOps

sealed trait Participant extends Product with Serializable

object Participant {
  case object Alice extends Participant
  case object Bob extends Participant
  case object Charlie extends Participant
  case object Dave extends Participant
  case object Eve extends Participant
  case object Faythe extends Participant
  case object Grace extends Participant
  case object Heidi extends Participant
  case object Ivan extends Participant
  case object Judy extends Participant
  case object Karl extends Participant
  case object Lance extends Participant
  case object Mallory extends Participant
  case object Niaj extends Participant
  case object Oscar extends Participant
  case object Peggy extends Participant
  case object Quentin extends Participant
  case object Ruth extends Participant
  case object Sybil extends Participant
  case object Trent extends Participant
  case object Ursula extends Participant
  case object Victor extends Participant
  case object Walter extends Participant
  case object Xavier extends Participant
  case object Yolanda extends Participant
  case object Zoe extends Participant

  final case class ParticipantData[F[_]](
    keyPair: KeyPair,
    address: Address,
    proof:   OttochainMessage => F[SignatureProof]
  )

  class ParticipantRegistry[F[_]: Async] private (private val map: Map[Participant, ParticipantData[F]]) {
    def apply(p: Participant): ParticipantData[F] = map(p)

    def addressJson: Map[Participant, String] = addresses.map(el => el._1 -> el._2.asJson.noSpaces)

    def addresses: Map[Participant, Address] = map.view.mapValues(_.address).toMap

    def generateProofs(update: OttochainMessage, signers: Set[Participant]): F[NonEmptySet[SignatureProof]] =
      signers.toList
        .traverse(p => map(p).proof(update))
        .flatMap(ps => NonEmptySet.fromSet(SortedSet.from(ps)).liftTo(new Exception("Empty proofs")))
  }

  object ParticipantRegistry {

    /** All 26 participants in alphabetical order, matching p12 index 1-26 */
    val all: List[Participant] = List(
      Alice,
      Bob,
      Charlie,
      Dave,
      Eve,
      Faythe,
      Grace,
      Heidi,
      Ivan,
      Judy,
      Karl,
      Lance,
      Mallory,
      Niaj,
      Oscar,
      Peggy,
      Quentin,
      Ruth,
      Sybil,
      Trent,
      Ursula,
      Victor,
      Walter,
      Xavier,
      Yolanda,
      Zoe
    )

    private val password: Array[Char] = "testpassword".toCharArray

    /** Load a KeyPair from a PKCS12 keystore input stream */
    private def loadKeyPairFromP12(is: InputStream, alias: String): KeyPair = {
      val ks = KeyStore.getInstance("PKCS12")
      ks.load(is, password)
      val privateKey = ks.getKey(alias, password).asInstanceOf[PrivateKey]
      val cert = ks.getCertificate(alias)
      new KeyPair(cert.getPublicKey, privateKey)
    }

    /**
     * Create a registry with randomly generated keys.
     * Use for tests that don't need deterministic addresses or token balances.
     */
    def create[F[_]: Async](
      participants: Set[Participant]
    )(implicit s: SecurityProvider[F]): F[ParticipantRegistry[F]] = {
      val sortedParticipants = participants.toList.sortBy(_.toString)
      sortedParticipants
        .traverse[F, ParticipantData[F]](_ =>
          KeyPairGenerator.makeKeyPair[F].map { sk =>
            ParticipantData(
              sk,
              sk.getPublic.toAddress,
              (msg: OttochainMessage) => msg.computeDigest.flatMap(SignatureProof.fromHash(sk, _))
            )
          }
        )
        .map { list =>
          new ParticipantRegistry(sortedParticipants.zip(list).toMap)
        }
    }

    /**
     * Load all 26 participants from static p12 keystores in test resources.
     * Provides deterministic addresses suitable for genesis.csv token allocation.
     *
     * Keystores are at: participants/participant_N.p12 (N=1..26)
     * All use password "testpassword" and alias "participant_N".
     */
    def fromResources[F[_]: Async]()(implicit s: SecurityProvider[F]): F[ParticipantRegistry[F]] =
      all.zipWithIndex
        .traverse[F, (Participant, ParticipantData[F])] { case (participant, idx) =>
          Async[F]
            .delay {
              val index = idx + 1
              val alias = s"participant_$index"
              val resourcePath = s"participants/participant_$index.p12"
              val is = getClass.getClassLoader.getResourceAsStream(resourcePath)
              if (is == null) throw new RuntimeException(s"Missing test resource: $resourcePath")
              try loadKeyPairFromP12(is, alias)
              finally is.close()
            }
            .map { kp =>
              participant -> ParticipantData[F](
                kp,
                kp.getPublic.toAddress,
                (msg: OttochainMessage) => msg.computeDigest.flatMap(SignatureProof.fromHash(kp, _))
              )
            }
        }
        .map(pairs => new ParticipantRegistry(pairs.toMap))
  }
}
