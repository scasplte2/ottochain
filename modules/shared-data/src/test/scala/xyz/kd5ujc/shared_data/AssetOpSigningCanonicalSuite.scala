package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.registry._

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import weaver.SimpleIOSuite

/**
 * Signing-canonical regression for the Phase-3 asset ops (`CreateAssetPolicy`, `MintAsset`, `ApplyMorphism`,
 * `AuthorizeCompose`). CLAUDE.md REQUIRES a case here for EVERY new signed message: a client signs
 * `JCS(dropNulls(payload))`; the chain verifies over `JCS(dropNulls(encode(decode(payload))))`. If
 * decode->encode injects a field the client OMITTED, `dropNulls` cannot reconcile it, the canonicals diverge,
 * and the message is rejected `InvalidSignature`.
 *
 * The invariant guarded (docs/signing-canonical-and-validation.md §1): every signed-message field is either
 * `Option[T]` (None -> null -> dropped, omit-safe) or REQUIRED with NO default.
 *
 * Method: build each op with EVERY `Option` field set to `None`, encode it, then `dropNulls` to simulate the
 * minimal payload a client actually signs (the client omits its `None`/null fields). Decode that payload back
 * and re-encode it the way the chain verifies. The two `dropNulls` canonicals MUST be identical — a
 * non-`Option` field carrying a default (`Boolean=false`, `SortedMap={}`, `Long=0`) would be present in the
 * re-encode but absent from the client payload, surfacing here as a mismatch.
 */
object AssetOpSigningCanonicalSuite extends SimpleIOSuite {

  /** Mirrors metakit JsonBinaryCodec.dropNulls: drop null object fields (array entries preserved). */
  private def dropNulls(j: Json): Json =
    j.arrayOrObject(
      j,
      arr => Json.fromValues(arr.map(dropNulls)),
      obj =>
        Json.fromJsonObject(
          JsonObject.fromIterable(obj.toIterable.collect { case (k, v) if !v.isNull => k -> dropNulls(v) })
        )
    )

  private val assetId = UUID.fromString("22222222-2222-4222-8222-222222222222")

  private val holder: Address =
    refineV[DAGAddressRefined].apply[String]("DAG6HdXmFyEwgKKdaEyAjU6SJPxGNAjUSbHgiRct") match {
      case Right(v) => Address(v)
      case Left(e)  => sys.error(s"bad test address: $e")
    }

  private val stateShape: MessageShape =
    MessageShape("Asset.State", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))

  // CreateAssetPolicy with metadata OMITTED (None) — supply/morphism-spec inner fields are all None too.
  private val createAssetPolicy: Updates.CreateAssetPolicy =
    Updates.CreateAssetPolicy(
      name = RegistryName.unsafe("gold.acme.asset"),
      version = SemVer(1, 0, 0),
      behavior = TokenBehavior.Fungible,
      supply = SupplyPolicy(None, None, None, None),
      morphisms = SortedMap(
        MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Public, None, None, None),
        MorphismKind.Burn     -> MorphismSpec(MorphismVisibility.Governed, None, None, None)
      ),
      stateShape = stateShape,
      metadata = None
    )

  // MintAsset with expiresAt + provenance OMITTED (None).
  private val mintAsset: Updates.MintAsset =
    Updates.MintAsset(
      assetId = assetId,
      policyRef = SchemaRef(RegistryName.unsafe("gold.acme.asset"), VersionReq.Latest),
      holder = AssetHolder.Wallet(holder),
      amount = 100L,
      expiresAt = None,
      provenance = None
    )

  // ApplyMorphism (Transfer) with every directive Option OMITTED (None).
  private val applyMorphism: Updates.ApplyMorphism =
    Updates.ApplyMorphism(
      assetId = assetId,
      kind = MorphismKind.Transfer,
      targetSequenceNumber = FiberOrdinal.MinValue,
      recipient = None,
      otherAssetIds = None,
      compositeId = None,
      shardIds = None
    )

  // AuthorizeCompose — every field is required (no defaults), nothing to omit.
  private val authorizeCompose: Updates.AuthorizeCompose =
    Updates.AuthorizeCompose(
      assetId = assetId,
      partnerPolicyId = RegistryName.unsafe("usd.acme.asset"),
      nonce = 7L,
      expiresAt = SnapshotOrdinal(NonNegLong.unsafeFrom(1000L)),
      targetSequenceNumber = FiberOrdinal.MinValue
    )

  private val cases: List[(String, OttochainMessage)] = List(
    "CreateAssetPolicy" -> createAssetPolicy,
    "MintAsset"         -> mintAsset,
    "ApplyMorphism"     -> applyMorphism,
    "AuthorizeCompose"  -> authorizeCompose
  )

  cases.foreach { case (name, msg) =>
    // The minimal client payload: encode, then drop nulls (the client omits its None fields when signing).
    val clientPayload: Json = dropNulls(msg.asJson)

    test(s"$name minimal client payload decodes against the chain OttochainMessage decoder") {
      IO.pure(decode[OttochainMessage](clientPayload.noSpaces) match {
        case Right(_)  => success
        case Left(err) => failure(s"DECODE FAILED for $name: $err")
      })
    }

    test(s"$name chain verify-canonical == client signed-canonical (no injected defaults)") {
      decode[OttochainMessage](clientPayload.noSpaces) match {
        case Right(decoded) => IO.pure(expect.same(dropNulls(decoded.asJson), clientPayload))
        case Left(err)      => IO.pure(failure(s"DECODE FAILED for $name: $err"))
      }
    }
  }
}
