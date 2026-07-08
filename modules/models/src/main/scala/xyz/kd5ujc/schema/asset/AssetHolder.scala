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

  /**
   * The magnolia wire-form keys — the variant discriminators (`Fiber`/`Wallet`) and their field names
   * (`fiberId`/`address`). The SINGLE SOURCE for any tooling that must reason about an `AssetHolder`'s static
   * shape WITHOUT a concrete value (e.g. the DefinitionLinter's `_transferAsset` recipient-shape check, which
   * sees an unresolved expression it cannot decode). Pinned to the actual codec output by a golden round-trip
   * test (`AssetHolderWireKeysSuite`), so renaming a case class / field can't silently drift these.
   */
  object WireKeys {
    val FiberVariant: String = "Fiber"
    val WalletVariant: String = "Wallet"
    val FiberIdField: String = "fiberId"
    val AddressField: String = "address"

    /** The two variant discriminator keys. */
    val variants: Set[String] = Set(FiberVariant, WalletVariant)

    /** The required inner field for a given variant discriminator, if known. */
    def fieldFor(variant: String): Option[String] = variant match {
      case FiberVariant  => Some(FiberIdField)
      case WalletVariant => Some(AddressField)
      case _             => None
    }
  }
}
