package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataOnChainState
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal}
import xyz.kd5ujc.schema.registry.RegistryName

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableDecoder, customizableEncoder)
case class FiberCommit(
  recordHash:     Hash,
  stateDataHash:  Option[Hash],
  sequenceNumber: FiberOrdinal
)

@derive(customizableDecoder, customizableEncoder)
case class OnChain(
  fiberCommits:    SortedMap[UUID, FiberCommit],
  latestLogs:      SortedMap[UUID, List[FiberLogEntry]],
  registryCommits: SortedMap[RegistryName, Hash] = SortedMap.empty
) extends DataOnChainState

object OnChain {
  val genesis: OnChain = OnChain(SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
