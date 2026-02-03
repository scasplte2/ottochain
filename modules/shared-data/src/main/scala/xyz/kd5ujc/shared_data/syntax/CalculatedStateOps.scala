package xyz.kd5ujc.shared_data.syntax

import java.util.UUID

import cats.Monad
import cats.data.EitherT
import cats.syntax.either._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError

import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.FiberRules.Errors

trait CalculatedStateOps {

  implicit class CalculatedStateSyntax(private val state: CalculatedState) {

    def getFiberRecord[F[_]: Monad](
      cid: UUID
    ): EitherT[F, DataApplicationValidationError, Records.StateMachineFiberRecord] =
      EitherT.fromOption[F](state.stateMachines.get(cid), Errors.FiberNotFound(cid)).flatMap { record =>
        EitherT.fromEither[F](record match {
          case value: Records.StateMachineFiberRecord => value.asRight[DataApplicationValidationError]
          case _ => Errors.MalformedFiberRecord.asLeft[Records.StateMachineFiberRecord]
        })
      }

    /** Lookup any fiber by ID */
    def getFiber(id: UUID): Option[Records.FiberRecord] =
      state.stateMachines.get(id).orElse(state.scripts.get(id))

    /** Update a fiber (dispatches to correct map) */
    def updateFiber(fiber: Records.FiberRecord): CalculatedState = fiber match {
      case sm: Records.StateMachineFiberRecord =>
        state.copy(stateMachines = state.stateMachines.updated(sm.fiberId, sm))
      case oracle: Records.ScriptFiberRecord =>
        state.copy(scripts = state.scripts.updated(oracle.fiberId, oracle))
    }
  }
}

object CalculatedStateOps extends CalculatedStateOps
