package xyz.kd5ujc.schema.asset

import java.util.UUID

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Who holds an asset instance. An asset's custody is either an ordinary wallet [[AssetHolder.Wallet]] or a
 * live fiber [[AssetHolder.Fiber]] — fiber-as-asset-holder is a first-class custody form (assets escrowed
 * inside a state machine, an AMM pool, a vault). See docs/proposals/asset-model.md §5e/§10.
 *
 * `Fiber(x)` custody carries a combiner-only liveness obligation (the fiber must be a live, non-archived
 * record) and an asset/fiber UUID-namespace disambiguation — both enforced in the asset combiner (Phase 4),
 * NOT here. This type is pure state shape.
 *
 * Wire form: the magnolia variant discriminator — `{"Wallet":{"address":..}}` / `{"Fiber":{"fiberId":..}}`
 * (the two variants have disjoint field-name sets, so the form is unambiguous either way).
 */
@derive(customizableEncoder, customizableDecoder)
sealed trait AssetHolder

object AssetHolder {

  /** Custody by an ordinary wallet address. */
  final case class Wallet(address: Address) extends AssetHolder

  /** Custody by a live fiber (escrow / pool / vault). Liveness is checked in the combiner (Phase 4). */
  final case class Fiber(fiberId: UUID) extends AssetHolder
}
