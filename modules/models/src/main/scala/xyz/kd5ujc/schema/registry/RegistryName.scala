package xyz.kd5ujc.schema.registry

import cats.{Order, Show}

import eu.timepit.refined.types.string.NonEmptyString

/**
 * A registry namespace name: a dotted hierarchy of DNS-like labels (lowercase alphanumeric + hyphen,
 * 1..63 chars each, no leading/trailing hyphen), e.g. `escrow`, `gov.threshold-dao`, `escrow.acme`.
 *
 * The wrapped [[NonEmptyString]] gives the compiler the non-empty guarantee; the full label/charset shape
 * is enforced at the construction boundary via [[from]] (the codebase's refined-wrapper + smart-constructor
 * idiom, cf. `FiberOrdinal`). Hierarchy is purely lexical for now; per-label ownership/delegation arrives
 * with the nickname registry (#29).
 *
 * TODO(naming #29): promote the dotted-label shape to a full type-level refinement (MatchesRegex) and add
 * hierarchical delegation semantics.
 */
final case class RegistryName(value: NonEmptyString) {
  def render: String = value.value
}

object RegistryName {

  final val MaxLength = 253
  private val Label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
  private val Pattern = s"^$Label(\\.$Label)*$$".r

  def from(raw: String): Either[String, RegistryName] =
    if (raw.length > MaxLength) Left(s"registry name too long (> $MaxLength): '$raw'")
    else if (!Pattern.matches(raw)) Left(s"invalid registry name '$raw' (dotted lowercase labels of [a-z0-9-])")
    else NonEmptyString.from(raw).map(RegistryName(_))

  def unsafe(raw: String): RegistryName =
    from(raw).fold(e => throw new IllegalArgumentException(e), identity)

  implicit val order: Order[RegistryName] = Order.by(_.value.value)
  implicit val ordering: Ordering[RegistryName] = order.toOrdering
  implicit val show: Show[RegistryName] = Show.show(_.value.value)
}
