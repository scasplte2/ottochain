package xyz.kd5ujc.schema

import io.constellationnetwork.currency.dataApplication.DataState

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Serializable wrapper around a genesis `DataState`.
 *
 * `DataState[OnChain, CalculatedState]` itself has no Circe codec, but each of its two halves
 * (`OnChain`, `CalculatedState`) does. This wrapper carries both so an operator can ship a
 * pre-built genesis as a single JSON document (see `GenesisLoader`), which the node decodes and
 * boots from instead of the empty genesis.
 */
@derive(customizableEncoder, customizableDecoder)
final case class GenesisData(
  onChain:    OnChain,
  calculated: CalculatedState
) {
  def toDataState: DataState[OnChain, CalculatedState] = DataState(onChain, calculated)
}

object GenesisData {
  def from(ds: DataState[OnChain, CalculatedState]): GenesisData = GenesisData(ds.onChain, ds.calculated)
}
