package xyz.kd5ujc.schema.registry

import cats.{Order, Show}

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

/**
 * A registry name: a dotted hierarchy of DNS-like labels plus a reserved top-level [[NameTld]] —
 * `.package` for a versioned schema/program package, `.machine`/`.script` for a fiber alias. E.g.
 * `escrow.package`, `gov.threshold-dao.package`, `my-escrow.machine`.
 *
 * The TLD is part of the key, so a package and a fiber alias can share label text under different TLDs
 * (`escrow.package` vs `escrow.machine`). `labels` is the dotted prefix (lowercase alphanumeric + hyphen,
 * 1..63 chars each, no leading/trailing hyphen); the full shape is enforced at the construction boundary
 * via [[from]] (the codebase's refined-wrapper + smart-constructor idiom). Hierarchy is lexical for now;
 * per-label ownership/delegation arrives with the nickname-registry follow-up (#29).
 */
final case class RegistryName(labels: NonEmptyString, tld: NameTld) {
  def render: String = s"${labels.value}.${tld.entryName}"
}

object RegistryName {

  final val MaxLength = 253
  private val Label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
  private val LabelsPattern = s"^$Label(\\.$Label)*$$".r

  /** Parse a full `"<labels>.<tld>"` string, e.g. `escrow.acme.package` or `my-fiber.machine`. */
  def from(raw: String): Either[String, RegistryName] = {
    val idx = raw.lastIndexOf('.')
    if (idx <= 0)
      Left(s"registry name '$raw' must be <labels>.<tld> (e.g. escrow.package, my-fiber.machine)")
    else {
      val labelsPart = raw.substring(0, idx)
      val tldPart = raw.substring(idx + 1)
      NameTld.withNameInsensitiveOption(tldPart) match {
        case None =>
          Left(
            s"invalid registry TLD '.$tldPart' (expected one of ${NameTld.values.map("." + _.entryName).mkString(", ")})"
          )
        case Some(tld) =>
          if (raw.length > MaxLength) Left(s"registry name too long (> $MaxLength): '$raw'")
          else if (!LabelsPattern.matches(labelsPart))
            Left(s"invalid registry name labels '$labelsPart' (dotted lowercase labels of [a-z0-9-])")
          else NonEmptyString.from(labelsPart).map(RegistryName(_, tld))
      }
    }
  }

  def unsafe(raw: String): RegistryName =
    from(raw).fold(e => throw new IllegalArgumentException(e), identity)

  implicit val order: Order[RegistryName] = Order.by(_.render)
  implicit val ordering: Ordering[RegistryName] = order.toOrdering
  implicit val show: Show[RegistryName] = Show.show(_.render)

  // Wire form is the full `labels.tld` string (also usable as a JSON map key).
  implicit val encoder: Encoder[RegistryName] = Encoder.encodeString.contramap(_.render)
  implicit val decoder: Decoder[RegistryName] = Decoder.decodeString.emap(from)
  implicit val keyEncoder: KeyEncoder[RegistryName] = KeyEncoder.encodeKeyString.contramap(_.render)
  implicit val keyDecoder: KeyDecoder[RegistryName] = KeyDecoder.instance(from(_).toOption)

  /**
   * Labels reserved in-protocol (e.g. `std`). A name using any reserved label is rejected at registration
   * — held pending the curator mechanism (trust-and-verification-handoff.md). Profanity and other
   * eligibility rules are enforced OFF-CHAIN at the Bridge, not here.
   */
  val ReservedLabels: Set[String] =
    Set("std", "system", "sys", "root", "admin", "registry", "protocol", "ottochain", "dag", "metagraph")

  /** True if any label of `name` is reserved in-protocol. */
  def isReserved(name: RegistryName): Boolean =
    name.labels.value.split('.').exists(ReservedLabels.contains)
}
