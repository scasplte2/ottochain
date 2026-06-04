package xyz.kd5ujc.schema.registry

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

/**
 * Semantic version `MAJOR.MINOR.PATCH` for registry entries.
 *
 * Mapping to schema/logic evolution (enforced by the registrar, see schema-architecture.md §6):
 * additive schema / new command → minor; breaking → major; logic-only → patch.
 */
final case class SemVer(major: Int, minor: Int, patch: Int) {
  def render: String = s"$major.$minor.$patch"
  def nextMajor: SemVer = SemVer(major + 1, 0, 0)
  def nextMinor: SemVer = SemVer(major, minor + 1, 0)
  def nextPatch: SemVer = SemVer(major, minor, patch + 1)
}

object SemVer {
  val Zero: SemVer = SemVer(0, 0, 0)

  implicit val ordering: Ordering[SemVer] = Ordering.by(v => (v.major, v.minor, v.patch))

  def parse(s: String): Either[String, SemVer] =
    s.split('.') match {
      case Array(a, b, c) =>
        (a.toIntOption, b.toIntOption, c.toIntOption) match {
          case (Some(x), Some(y), Some(z)) if x >= 0 && y >= 0 && z >= 0 => Right(SemVer(x, y, z))
          case _                                                         => Left(s"invalid semver '$s'")
        }
      case _ => Left(s"invalid semver '$s': expected MAJOR.MINOR.PATCH")
    }

  // Wire form is the string "1.2.3" (also usable as a JSON map key).
  implicit val encoder: Encoder[SemVer] = Encoder.encodeString.contramap(_.render)
  implicit val decoder: Decoder[SemVer] = Decoder.decodeString.emap(parse)
  implicit val keyEncoder: KeyEncoder[SemVer] = KeyEncoder.encodeKeyString.contramap(_.render)
  implicit val keyDecoder: KeyDecoder[SemVer] = KeyDecoder.instance(parse(_).toOption)
}
