package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataCalculatedState

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class CalculatedState(
  stateMachines: SortedMap[UUID, Records.StateMachineFiberRecord],
  scripts:       SortedMap[UUID, Records.ScriptFiberRecord]
) extends DataCalculatedState

object CalculatedState {
  val genesis: CalculatedState = CalculatedState(SortedMap.empty, SortedMap.empty)
}
