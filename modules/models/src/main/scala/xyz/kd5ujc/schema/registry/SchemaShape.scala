package xyz.kd5ujc.schema.registry

import scala.collection.immutable.SortedMap

import xyz.kd5ujc.schema.CodecConfiguration._

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
  repeated: Boolean = false,
  optional: Boolean = false
)

@derive(customizableEncoder, customizableDecoder)
final case class MessageShape(
  typeName: String,
  fields:   List[FieldShape]
)

/**
 * The on-chain projection of a version's proto schema: the State message plus one message per command/event
 * (keyed by event name). Supersedes the old loose `stateMessage: String` + `commands: SortedMap[String,
 * String]` with the typed, field-numbered shape.
 */
@derive(customizableEncoder, customizableDecoder)
final case class SchemaShape(
  stateMessage: MessageShape,
  commands:     SortedMap[String, MessageShape]
) {

  /** Every message in the shape (state + all commands), for structural validation. */
  def allMessages: List[MessageShape] = stateMessage :: commands.values.toList
}
