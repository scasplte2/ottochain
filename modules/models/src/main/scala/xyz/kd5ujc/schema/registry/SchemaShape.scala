package xyz.kd5ujc.schema.registry

import scala.collection.immutable.SortedMap

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.asset.{MorphismKind, MorphismSpec, SupplyPolicy, TokenBehavior}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A shallow, proto-faithful projection of a registered schema — the typed *domain definition* the chain
 * stores for discovery (agents, UIs, codegen) without ever parsing protobuf. It is publisher-claimed and
 * advisory: the chain commits the full descriptor's [[RegisteredVersion.schemaHash]] and trusts this
 * projection; the Bridge re-derives and verifies it against the descriptor off-chain (and may promote the
 * check on-chain later). See docs/proposals/strong-typing-and-conformance.md §0.5 — this is the "describe"
 * dial, which never constrains the JLVM logic.
 *
 * One [[FieldShape]] mirrors a protobuf `FieldDescriptorProto` at the field level: name + field number +
 * type. Nested/referenced message types are named by `typeName`; their full shape lives in the off-chain
 * descriptor, not here.
 */
@derive(customizableEncoder, customizableDecoder)
final case class FieldShape(
  name:     String,
  number:   Int,
  typeName: String,
  repeated: Boolean,
  optional: Boolean
)

@derive(customizableEncoder, customizableDecoder)
final case class MessageShape(
  typeName: String,
  fields:   List[FieldShape]
)

/**
 * The on-chain projection of a state-machine version's proto schema: the State message plus one message per
 * command/event (keyed by event name). Supersedes the old loose `stateMessage: String` + `commands:
 * SortedMap[String, String]` with the typed, field-numbered shape.
 */
@derive(customizableEncoder, customizableDecoder)
final case class MachineShape(
  stateMessage: MessageShape,
  commands:     SortedMap[String, MessageShape]
) {

  /** Every message in the shape (state + all commands), for structural validation. */
  def allMessages: List[MessageShape] = stateMessage :: commands.values.toList
}

/**
 * The on-chain projection of a script version's surface. Sealed ADT — currently one variant:
 * [[ScriptShape.MethodDispatch]], the canonical top-level IF-ELIF-…-ELSE dispatch on method name. Future
 * variants (pipelines, etc.) are added here without breaking the [[RegistryShape]] wire format (the
 * `scriptShape` field in [[RegistryShape.Script]] naturally discriminates on the inner field names).
 */
sealed trait ScriptShape {
  def allMessages: List[MessageShape]
}

object ScriptShape {

  /**
   * The canonical script form: a top-level `{"if":[{"==":[{"var":"method"},"methodA"]},bodyA,...,fallback]}`
   * dispatch. Each key in `methods` names a callable method; its [[MessageShape]] describes the argument
   * payload the method expects.
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class MethodDispatch(
    methods: SortedMap[String, MessageShape]
  ) extends ScriptShape {
    def allMessages: List[MessageShape] = methods.values.toList
  }

  // MethodDispatch encodes as {"methods":{...}} — the "methods" key discriminates from future variants.
  implicit val encoder: io.circe.Encoder[ScriptShape] = io.circe.Encoder.instance { case m: MethodDispatch =>
    io.circe.Encoder[MethodDispatch].apply(m)
  }

  implicit val decoder: io.circe.Decoder[ScriptShape] =
    io.circe.Decoder[MethodDispatch].map[ScriptShape](identity)
}

/**
 * ADT for the advisory schema projection stored in [[RegisteredVersion]]. Exactly one variant is present:
 * [[RegistryShape.Machine]] for state-machine packages; [[RegistryShape.Script]] for script packages;
 * [[RegistryShape.AssetPolicy]] for asset-policy packages (docs/proposals/asset-model.md §5a).
 */
sealed trait RegistryShape {
  def allMessages: List[MessageShape]
}

object RegistryShape {

  @derive(customizableEncoder, customizableDecoder)
  final case class Machine(machineShape: MachineShape) extends RegistryShape {
    def allMessages: List[MessageShape] = machineShape.allMessages
  }

  @derive(customizableEncoder, customizableDecoder)
  final case class Script(scriptShape: ScriptShape) extends RegistryShape {
    def allMessages: List[MessageShape] = scriptShape.allMessages
  }

  /**
   * The on-chain projection of an asset-policy version: the instance [[TokenBehavior]], the [[SupplyPolicy]]
   * (mint/burn/cap authority), the per-kind [[MorphismSpec]] table, and the asset-state [[MessageShape]]
   * (for the strict conformance gate, asset-model.md §5d). `morphisms` is a REQUIRED field (no
   * `= SortedMap.empty` default — signing-canonical invariant #1: emptiness is meaningful and must be sent
   * explicitly, never defaulted by the decoder).
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class AssetPolicy(
    behavior:   TokenBehavior,
    supply:     SupplyPolicy,
    morphisms:  SortedMap[MorphismKind, MorphismSpec],
    stateShape: MessageShape
  ) extends RegistryShape {
    def allMessages: List[MessageShape] = List(stateShape)
  }

  // Machine encodes as {"machineShape":{...}}, Script as {"scriptShape":{...}}, AssetPolicy as
  // {"behavior":..,"supply":..,"morphisms":..,"stateShape":..} — disjoint field-name sets act as a
  // natural discriminator, no explicit type tag needed.
  implicit val encoder: io.circe.Encoder[RegistryShape] = io.circe.Encoder.instance {
    case m: Machine     => io.circe.Encoder[Machine].apply(m)
    case s: Script      => io.circe.Encoder[Script].apply(s)
    case a: AssetPolicy => io.circe.Encoder[AssetPolicy].apply(a)
  }

  implicit val decoder: io.circe.Decoder[RegistryShape] =
    io.circe
      .Decoder[Machine]
      .map[RegistryShape](identity)
      .or(io.circe.Decoder[Script].map[RegistryShape](identity))
      .or(io.circe.Decoder[AssetPolicy].map[RegistryShape](identity))
}
