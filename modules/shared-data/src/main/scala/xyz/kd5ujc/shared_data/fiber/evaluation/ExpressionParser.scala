package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.data.OptionT
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber._

/**
 * Parses JsonLogicExpression and JsonLogicValue into domain types.
 *
 * Provides bidirectional conversion between:
 * - JsonLogicExpression ↔ StateMachineDefinition, Transition, State
 * - JsonLogicValue ↔ same domain types
 * - JsonLogicValue → JsonLogicExpression (valueToExpression)
 */
object ExpressionParser {

  /** Parse a canonical UUID string; malformed → `None`. Shared by every UUID-bearing parse site. */
  private[evaluation] def parseUuid(raw: String): Option[UUID] =
    scala.util.Try(UUID.fromString(raw)).toOption

  def parseStateMachineDefinitionFromExpression(
    defExpr: JsonLogicExpression
  ): Option[StateMachineDefinition] =
    defExpr match {
      case MapExpression(defMap) =>
        (for {
          statesExpr       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states           <- OptionT.fromOption[cats.Id](parseStatesFromExpression(statesExpr))
          initialStateExpr <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState     <- OptionT.fromOption[cats.Id](parseInitialState(initialStateExpr))
          transitionsExpr  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions      <- OptionT.fromOption[cats.Id](parseTransitionsFromExpression(transitionsExpr))
          metadata = defMap.get(ReservedKeys.METADATA).collect { case ConstExpression(v) => v }
        } yield StateMachineDefinition(states, initialState, transitions, metadata)).value

      case ConstExpression(v) =>
        parseStateMachineDefinition(v)

      case _ => none
    }

  private def parseInitialState(expr: JsonLogicExpression): Option[StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateId(s).some
      case MapExpression(m) => m.get(ReservedKeys.VALUE).collect { case ConstExpression(StrValue(s)) => StateId(s) }
      case _                => none
    }

  private def parseStatesFromExpression(
    statesExpr: JsonLogicExpression
  ): Option[Map[StateId, State]] =
    statesExpr match {
      case MapExpression(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateExpr) =>
            stateExpr match {
              case MapExpression(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .collect { case ConstExpression(BoolValue(b)) => b }
                  .getOrElse(false)
                val metadata = stateMap.get(ReservedKeys.METADATA).collect { case ConstExpression(v) => v }
                (StateId(stateId) -> State(
                  id = StateId(stateId),
                  isFinal = isFinal,
                  metadata = metadata
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  private def parseTransitionsFromExpression(
    transitionsExpr: JsonLogicExpression
  ): Option[List[Transition]] =
    transitionsExpr match {
      case ArrayExpression(transitionsList) =>
        transitionsList.traverse {
          case MapExpression(transMap) =>
            (for {
              fromExpr      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from          <- OptionT.fromOption[cats.Id](parseStateId(fromExpr))
              toExpr        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to            <- OptionT.fromOption[cats.Id](parseStateId(toExpr))
              eventNameExpr <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_NAME))
              eventName     <- OptionT.fromOption[cats.Id](parseEventName(eventNameExpr))
              guard         <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effect        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
              dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .collect { case ArrayExpression(deps) =>
                  deps.flatMap {
                    case ConstExpression(StrValue(id)) => parseUuid(id)
                    case _                             => none
                  }.toSet
                }
                .getOrElse(Set.empty)
            } yield Transition(from, to, eventName, guard, effect, dependencies)).value
          case _ => none
        }
      case _ => none
    }

  private def parseStateId(expr: JsonLogicExpression): Option[StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateId(s).some
      case MapExpression(m) => m.get(ReservedKeys.VALUE).collect { case ConstExpression(StrValue(s)) => StateId(s) }
      case _                => none
    }

  private def parseEventName(expr: JsonLogicExpression): Option[String] =
    expr match {
      case ConstExpression(StrValue(et)) => et.some
      case MapExpression(m) => m.get(ReservedKeys.VALUE).collect { case ConstExpression(StrValue(et)) => et }
      case _                => none
    }

  def parseStateMachineDefinition(defValue: JsonLogicValue): Option[StateMachineDefinition] =
    defValue match {
      case MapValue(defMap) =>
        (for {
          statesValue       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states            <- OptionT.fromOption[cats.Id](parseStates(statesValue))
          initialStateValue <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState      <- OptionT.fromOption[cats.Id](parseInitialStateValue(initialStateValue))
          transitionsValue  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions       <- OptionT.fromOption[cats.Id](parseTransitions(transitionsValue))
        } yield StateMachineDefinition(
          states = states,
          initialState = initialState,
          transitions = transitions,
          metadata = defMap.get(ReservedKeys.METADATA)
        )).value
      case _ => none
    }

  private def parseInitialStateValue(value: JsonLogicValue): Option[StateId] =
    value match {
      case StrValue(s) => StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateId(s) }
      case _           => none
    }

  def valueToExpression(value: JsonLogicValue): JsonLogicExpression = value match {
    case MapValue(m) if m.size == 1 && m.contains(ReservedKeys.VAR) =>
      m.get(ReservedKeys.VAR) match {
        case Some(StrValue(path))                               => VarExpression(Left(path), none)
        case Some(ArrayValue(List(StrValue(path), defaultVal))) => VarExpression(Left(path), defaultVal.some)
        case Some(ArrayValue(List(StrValue(path))))             => VarExpression(Left(path), none)
        case Some(other)                                        => VarExpression(Right(valueToExpression(other)), none)
        case None                                               => ConstExpression(value)
      }
    case MapValue(m)   => MapExpression(m.view.mapValues(valueToExpression).toMap)
    case ArrayValue(a) => ArrayExpression(a.map(valueToExpression))
    case other         => ConstExpression(other)
  }

  private def parseStates(statesValue: JsonLogicValue): Option[Map[StateId, State]] =
    statesValue match {
      case MapValue(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateValue) =>
            stateValue match {
              case MapValue(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .collect { case BoolValue(b) =>
                    b
                  }
                  .getOrElse(false)
                (StateId(stateId) -> State(
                  id = StateId(stateId),
                  isFinal = isFinal,
                  metadata = stateMap.get(ReservedKeys.METADATA)
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  private def parseTransitions(transitionsValue: JsonLogicValue): Option[List[Transition]] =
    transitionsValue match {
      case ArrayValue(transitionsList) =>
        transitionsList.traverse {
          case MapValue(transMap) =>
            (for {
              fromValue      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from           <- OptionT.fromOption[cats.Id](parseStateIdValue(fromValue))
              toValue        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to             <- OptionT.fromOption[cats.Id](parseStateIdValue(toValue))
              eventTypeValue <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_NAME))
              eventType      <- OptionT.fromOption[cats.Id](parseEventNameValue(eventTypeValue))
              guardValue     <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effectValue    <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
              dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .collect { case ArrayValue(deps) =>
                  deps.flatMap {
                    case StrValue(id) => parseUuid(id)
                    case _            => none
                  }.toSet
                }
                .getOrElse(Set.empty)
            } yield Transition(
              from = from,
              to = to,
              eventName = eventType,
              guard = valueToExpression(guardValue),
              effect = valueToExpression(effectValue),
              dependencies = dependencies
            )).value
          case _ => none
        }
      case _ => none
    }

  private def parseStateIdValue(value: JsonLogicValue): Option[StateId] =
    value match {
      case StrValue(s) => StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateId(s) }
      case _           => none
    }

  private def parseEventNameValue(value: JsonLogicValue): Option[String] =
    value match {
      case StrValue(et) => et.some
      case MapValue(m)  => m.get(ReservedKeys.VALUE).collect { case StrValue(et) => et }
      case _            => none
    }
}
