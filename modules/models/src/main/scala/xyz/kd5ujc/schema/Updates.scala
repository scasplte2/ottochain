package xyz.kd5ujc.schema

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, FiberOrdinal, StateMachineDefinition}
import xyz.kd5ujc.schema.registry.{RegistryName, RegistryStatus, SchemaRef, SchemaShape, SemVer}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

object Updates {

  sealed trait OttochainMessage extends DataUpdate {
    lazy val messageName: String = this.getClass.getSimpleName
    val fiberId: UUID
  }

  /**
   * Mixin trait for operations that require sequence-based ordering.
   *
   * Operations that mutate fiber state must be processed in sequence order.
   * This trait enables a canonical ordering across all OttochainMessage types,
   * ensuring that sequenced operations are processed after creates and in
   * their correct sequence order within each fiber.
   */
  trait Sequenced {
    def fiberId: UUID
    def targetSequenceNumber: FiberOrdinal
  }

  sealed trait StateMachineFiberOp

  @derive(customizableDecoder, customizableEncoder)
  final case class CreateStateMachine(
    fiberId:       UUID,
    definition:    StateMachineDefinition,
    initialData:   JsonLogicValue,
    parentFiberId: Option[UUID] = None,
    schemaRef:     Option[SchemaRef] = None,
    participants:  Option[Set[Address]] = None
  ) extends StateMachineFiberOp
      with OttochainMessage

  /**
   * Event to trigger a state machine transition.
   *
   * @param fiberId Target fiber CID
   * @param eventName Type of event to trigger
   * @param payload Event payload data
   */
  @derive(customizableDecoder, customizableEncoder)
  final case class TransitionStateMachine(
    fiberId:              UUID,
    eventName:            String,
    payload:              JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage
      with Sequenced

  @derive(customizableDecoder, customizableEncoder)
  final case class ArchiveStateMachine(
    fiberId:              UUID,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage
      with Sequenced

  /**
   * Upgrade an existing fiber to a different registered version of the SAME package (#27). The chain
   * verifies `newDefinition.computeDigest == targetVersion.logicHash` (verified re-bind), applies the
   * optional `migration` (a JSON-Logic transform of the prior stateData) through the engine's metered
   * evaluator, preserves the current state id (which must exist in `newDefinition`), and re-pins the
   * binding. The commute-law is NOT verified on-chain — see trust-and-verification-handoff.md.
   */
  @derive(customizableDecoder, customizableEncoder)
  final case class UpgradeFiber(
    fiberId:              UUID,
    targetRef:            SchemaRef,
    newDefinition:        StateMachineDefinition,
    migration:            Option[JsonLogicExpression] = None,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage
      with Sequenced

  sealed trait ScriptFiberOp

  @derive(customizableDecoder, customizableEncoder)
  final case class CreateScript(
    fiberId:       UUID,
    scriptProgram: JsonLogicExpression,
    initialState:  Option[JsonLogicValue],
    accessControl: AccessControlPolicy
  ) extends ScriptFiberOp
      with OttochainMessage

  @derive(customizableDecoder, customizableEncoder)
  final case class InvokeScript(
    fiberId:              UUID,
    method:               String,
    args:                 JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends ScriptFiberOp
      with OttochainMessage
      with Sequenced

  sealed trait RegistryOp {
    def name: RegistryName
  }

  object RegistryOp {

    /** Deterministic routing/ordering key derived from the registry name — a routing key, NOT a fiber. */
    def routingId(name: RegistryName): UUID =
      UUID.nameUUIDFromBytes(s"registry:${name.render}".getBytes(StandardCharsets.UTF_8))
  }

  /**
   * Create-or-append a registry version (npm-publish semantics): the first publish for a name claims it and
   * makes the signer the owner; later publishes require an existing owner.
   *
   *  - `schemaB64`: the full protobuf FileDescriptorSet (≈ deployed bytecode). The chain validates it is
   *    base64, hashes it into `schemaHash`, and drops the bytes — they live in the registration update's
   *    history + the Bridge store (Etherscan-style claim; schema-architecture.md §4a).
   *  - `schemaShape`: the typed, proto-friendly projection the chain stores for discovery (advisory).
   *  - `definition`: the typed JSON-Logic state machine. The chain hashes it into `logicHash` via
   *    `computeDigest` — the same canonical digest a fiber computes — enabling VERIFIED binding (#37): a
   *    fiber referencing this version is admitted only if its definition hashes to `logicHash`. The guards
   *    and effects inside stay generic `JsonLogicExpression`; typing the container does not constrain the
   *    computation.
   *
   * The owner is derived from the signing proofs at combine time.
   */
  @derive(customizableDecoder, customizableEncoder)
  final case class PublishVersion(
    name:        RegistryName,
    version:     SemVer,
    schemaB64:   String,
    schemaShape: SchemaShape,
    definition:  StateMachineDefinition,
    strict:      Boolean,
    // Optional off-chain links grab-bag (None == omitted), set on the entry at first publish.
    metadata: Option[SortedMap[String, String]] = None
  ) extends RegistryOp
      with OttochainMessage {
    val fiberId: UUID = RegistryOp.routingId(name)
  }

  /** Change a registered version's lifecycle status (Active <-> Deprecated -> Yanked). Owner-gated. */
  @derive(customizableDecoder, customizableEncoder)
  final case class SetVersionStatus(
    name:    RegistryName,
    version: SemVer,
    status:  RegistryStatus
  ) extends RegistryOp
      with OttochainMessage {
    val fiberId: UUID = RegistryOp.routingId(name)
  }

  /**
   * Register a human-readable nickname for an existing fiber (#29). The name's TLD must be `.machine` or
   * `.script` and must match the target fiber's kind; the signer must own the target fiber. Sets the
   * forward alias (name -> fiber) and the fiber's canonical reverse record (fiber -> name).
   */
  @derive(customizableDecoder, customizableEncoder)
  final case class RegisterAlias(
    name:          RegistryName,
    targetFiberId: UUID,
    metadata:      Option[SortedMap[String, String]] = None
  ) extends RegistryOp
      with OttochainMessage {
    val fiberId: UUID = RegistryOp.routingId(name)
  }

  object OttochainMessage {

    /**
     * Canonical ordering for OttochainMessage.
     *
     * Ordering rules:
     * 1. Non-sequenced messages (Creates) come first - they initialize fibers
     * 2. Sequenced messages are ordered by (fiberId, targetSequenceNumber)
     *
     * This ensures that within a batch of updates:
     * - Fiber creation happens before any transitions on that fiber
     * - Transitions for the same fiber are processed in sequence order
     * - Different fibers can interleave but each fiber's ops are sequential
     */
    implicit val ordering: Ordering[OttochainMessage] = Ordering.by {
      case s: Sequenced => (1, s.fiberId.toString, s.targetSequenceNumber.value.value)
      case m            => (0, m.fiberId.toString, 0L)
    }

    /**
     * Ordering for Signed[OttochainMessage] - delegates to message ordering.
     */
    implicit val signedOrdering: Ordering[Signed[OttochainMessage]] =
      Ordering.by(_.value)

    implicit val messageEncoder: Encoder[OttochainMessage] = {
      case u: Updates.CreateStateMachine     => Json.obj(u.messageName -> u.asJson)
      case u: Updates.TransitionStateMachine => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ArchiveStateMachine    => Json.obj(u.messageName -> u.asJson)
      case u: Updates.UpgradeFiber           => Json.obj(u.messageName -> u.asJson)
      case u: Updates.CreateScript           => Json.obj(u.messageName -> u.asJson)
      case u: Updates.InvokeScript           => Json.obj(u.messageName -> u.asJson)
      case u: Updates.PublishVersion         => Json.obj(u.messageName -> u.asJson)
      case u: Updates.SetVersionStatus       => Json.obj(u.messageName -> u.asJson)
      case u: Updates.RegisterAlias          => Json.obj(u.messageName -> u.asJson)
    }

    implicit val messageDecoder: Decoder[OttochainMessage] =
      (c: HCursor) => {
        val decoders = List(
          Decoder[Updates.CreateStateMachine],
          Decoder[Updates.TransitionStateMachine],
          Decoder[Updates.ArchiveStateMachine],
          Decoder[Updates.UpgradeFiber],
          Decoder[Updates.CreateScript],
          Decoder[Updates.InvokeScript],
          Decoder[Updates.PublishVersion],
          Decoder[Updates.SetVersionStatus],
          Decoder[Updates.RegisterAlias]
        )

        c.keys
          .flatMap(_.headOption)
          .flatMap { field =>
            c.downField(field).success.map { fieldCursor =>
              decoders
                .map(_.tryDecode(fieldCursor))
                .collectFirst { case right @ Right(v) if v.messageName == field => right }
                .getOrElse(Left(DecodingFailure("Cannot decode as OttochainMessage", c.history)))
            }
          }
          .getOrElse(Left(DecodingFailure("Cannot decode as OttochainMessage: JSON is empty", Nil)))
      }
  }
}
