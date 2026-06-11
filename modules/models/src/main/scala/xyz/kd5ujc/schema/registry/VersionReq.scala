package xyz.kd5ujc.schema.registry

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A caller's version requirement, resolved deterministically against a [[VersionLineage]] (Cargo/npm-style).
 *
 *  - Exact      — exactly this version.
 *  - Caret(v)   — latest selectable with the same MAJOR and `>= v` (`^1.2.0`).
 *  - Tilde(v)   — latest selectable with the same MAJOR.MINOR and `>= v` (`~1.2.0`).
 *  - Latest     — highest selectable version.
 *  - PinnedHash — the exact artifact by schema hash, version-agnostic.
 *
 * "Selectable" excludes Yanked; Deprecated is selectable but flagged.
 */
@derive(customizableEncoder, customizableDecoder)
sealed trait VersionReq

object VersionReq {
  final case class Exact(version: SemVer) extends VersionReq
  final case class Caret(version: SemVer) extends VersionReq
  final case class Tilde(version: SemVer) extends VersionReq
  case object Latest extends VersionReq
  final case class PinnedHash(schemaHash: Hash) extends VersionReq
}
