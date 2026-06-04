package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataCalculatedState

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.{RegistryEntry, RegistryName}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder)
case class CalculatedState(
  stateMachines: SortedMap[UUID, Records.StateMachineFiberRecord],
  scripts:       SortedMap[UUID, Records.ScriptFiberRecord],
  registry:      SortedMap[RegistryName, RegistryEntry] = SortedMap.empty
) extends DataCalculatedState

object CalculatedState {
  val genesis: CalculatedState = CalculatedState(SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
