package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}

import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, FiberOrdinal, StateMachineDefinition}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

object Updates {

  sealed trait OttochainMessage extends DataUpdate {
    lazy val messageName: String = this.getClass.getSimpleName
    val fiberId: UUID
  }

  sealed trait StateMachineFiberOp

  @derive(decoder, encoder)
  final case class CreateStateMachine(
    fiberId:       UUID,
    definition:    StateMachineDefinition,
    initialData:   JsonLogicValue,
    parentFiberId: Option[UUID] = None
  ) extends StateMachineFiberOp
      with OttochainMessage

  /**
   * Event to trigger a state machine transition.
   *
   * @param fiberId Target fiber CID
   * @param eventName Type of event to trigger
   * @param payload Event payload data
   */
  @derive(decoder, encoder)
  final case class TransitionStateMachine(
    fiberId:              UUID,
    eventName:            String,
    payload:              JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class ArchiveStateMachine(
    fiberId:              UUID,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage

  sealed trait ScriptFiberOp

  @derive(decoder, encoder)
  final case class CreateScript(
    fiberId:       UUID,
    scriptProgram: JsonLogicExpression,
    initialState:  Option[JsonLogicValue],
    accessControl: AccessControlPolicy
  ) extends ScriptFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class InvokeScript(
    fiberId:              UUID,
    method:               String,
    args:                 JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends ScriptFiberOp
      with OttochainMessage

  object OttochainMessage {

    implicit val messageEncoder: Encoder[OttochainMessage] = {
      case u: Updates.CreateStateMachine     => Json.obj(u.messageName -> u.asJson)
      case u: Updates.TransitionStateMachine => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ArchiveStateMachine    => Json.obj(u.messageName -> u.asJson)
      case u: Updates.CreateScript           => Json.obj(u.messageName -> u.asJson)
      case u: Updates.InvokeScript           => Json.obj(u.messageName -> u.asJson)
    }

    implicit val messageDecoder: Decoder[OttochainMessage] =
      (c: HCursor) => {
        val decoders = List(
          Decoder[Updates.CreateStateMachine],
          Decoder[Updates.TransitionStateMachine],
          Decoder[Updates.ArchiveStateMachine],
          Decoder[Updates.CreateScript],
          Decoder[Updates.InvokeScript]
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
