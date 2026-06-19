package xyz.kd5ujc.metagraph_l0.openapi

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.asset.{AssetHolder, MorphismKind, MorphismVisibility}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry._

import sttp.tapir.{Schema, SchemaType}

/**
 * tapir `Schema` instances for the leaf domain types that `generic.auto` can't derive on its own — refined
 * newtypes (string- or integer-encoded) and the JsonLogic ADTs. With these in scope, `OnChain`, the
 * fiber/script/asset/registry records, and the state proofs derive their OpenAPI schemas automatically —
 * NO changes to the `models` case classes and NO tapir dependency in `models`.
 *
 * Each schema matches the circe wire encoding (verified by TypedApiWireSuite). `JsonLogicValue` /
 * `JsonLogicExpression` are `any`: they're arbitrary, application-defined data (per-app state, event
 * payloads, the logic AST) that the chain deliberately doesn't constrain — `any` is the correct schema.
 */
object DomainSchemas {

  // string-encoded newtypes (Encoder.encodeString.contramap / AnyVal string / CirceEnum)
  implicit val hash: Schema[Hash] = Schema.string
  implicit val address: Schema[Address] = Schema.string
  implicit val registryName: Schema[RegistryName] = Schema.string
  implicit val stateId: Schema[StateId] = Schema.string
  implicit val semVer: Schema[SemVer] = Schema.string

  // string-encoded enums (enumeratum CirceEnum) — otherwise auto-derived as coproducts, which lie
  implicit val fiberStatus: Schema[FiberStatus] = Schema.string
  implicit val fiberKind: Schema[FiberKind] = Schema.string
  implicit val inputKind: Schema[InputKind] = Schema.string
  implicit val gasExhaustionPhase: Schema[GasExhaustionPhase] = Schema.string
  implicit val morphismKind: Schema[MorphismKind] = Schema.string
  implicit val morphismVisibility: Schema[MorphismVisibility] = Schema.string
  implicit val nameTld: Schema[NameTld] = Schema.string
  implicit val registryStatus: Schema[RegistryStatus] = Schema.string

  // integer-encoded ordinals (FiberOrdinal: Encoder[Long].contramap; SnapshotOrdinal: pinned by test)
  implicit val fiberOrdinal: Schema[FiberOrdinal] = Schema(SchemaType.SInteger()).format("int64")
  implicit val snapshotOrdinal: Schema[SnapshotOrdinal] = Schema(SchemaType.SInteger()).format("int64")

  // arbitrary application-defined JSON — genuinely opaque
  implicit val jsonLogicValue: Schema[JsonLogicValue] = Schema.any
  implicit val jsonLogicExpression: Schema[JsonLogicExpression] = Schema.any

  // the JLVM state-machine program (states/transitions/guards/effects) is the user's "code" — the AST is
  // arbitrary and deep, so it's `any` (like stateData). The record ENVELOPE around it stays fully typed.
  implicit val stateMachineDefinition: Schema[StateMachineDefinition] = Schema.any

  // sealed-trait sum types: circe-magnolia encodes these with external tagging (`{"Case": {...}}`, like
  // OttochainMessage), which a bare tapir `oneOf` would misrepresent — so they're `any` at the union
  // position. The concrete case types stay typed components where endpoints use them directly.
  implicit val fiberLogEntry: Schema[FiberLogEntry] = Schema.any
  implicit val accessControlPolicy: Schema[AccessControlPolicy] = Schema.any
  implicit val assetHolder: Schema[AssetHolder] = Schema.any
  implicit val registryTarget: Schema[RegistryTarget] = Schema.any
  implicit val registryShape: Schema[RegistryShape] = Schema.any
  implicit val scriptShape: Schema[ScriptShape] = Schema.any

  // SortedMap renders as a JSON object keyed by the (string-encoded) key; element schema is V's
  implicit def sortedMap[K, V](implicit v: Schema[V]): Schema[SortedMap[K, V]] =
    Schema.schemaForMap[V](v).as[SortedMap[K, V]]
}
