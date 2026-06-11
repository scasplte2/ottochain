package xyz.kd5ujc.schema.registry

import scala.collection.immutable.SortedMap

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The append-only, immutable, monotonic version lineage of a single registry entry — the
 * content-agnostic core the chain enforces (no protobuf/JSON-Logic parsing here).
 *
 *  - `publish` appends a strictly-greater version; an existing version is never overwritten.
 *  - `setStatus` changes only `status`, by a legal transition; the rest of a version is immutable.
 *  - `resolve` deterministically picks a version for a [[VersionReq]], excluding Yanked.
 */
@derive(customizableEncoder, customizableDecoder)
final case class VersionLineage(versions: SortedMap[SemVer, RegisteredVersion]) {

  def get(v: SemVer): Option[RegisteredVersion] = versions.get(v)

  /** Highest version regardless of status. */
  def head: Option[RegisteredVersion] = versions.values.lastOption

  /** Append a new version: must not already exist (immutability) and must exceed all existing (monotonic). */
  def publish(rv: RegisteredVersion): Either[RegistryError, VersionLineage] =
    if (versions.contains(rv.version)) Left(RegistryError.VersionExists(rv.version))
    else
      versions.keys.lastOption match {
        case Some(max) if SemVer.ordering.gteq(max, rv.version) => Left(RegistryError.NonMonotonic(rv.version, max))
        case _ => Right(VersionLineage(versions.updated(rv.version, rv)))
      }

  /** Transition a version's status (the only mutable field), enforcing legal transitions. */
  def setStatus(v: SemVer, to: RegistryStatus): Either[RegistryError, VersionLineage] =
    versions.get(v) match {
      case None => Left(RegistryError.VersionNotFound(v))
      case Some(rv) =>
        if (!RegistryStatus.canTransition(rv.status, to)) Left(RegistryError.IllegalStatusTransition(rv.status, to))
        else Right(VersionLineage(versions.updated(v, rv.copy(status = to))))
    }

  /** Deterministically resolve a requirement; Yanked versions are never selectable. */
  def resolve(req: VersionReq): Either[RegistryError, RegisteredVersion] = {
    def selectable(rv: RegisteredVersion): Boolean = rv.status != RegistryStatus.Yanked
    val pool = versions.values.filter(selectable)
    val out = req match {
      case VersionReq.Exact(v)      => versions.get(v).filter(selectable)
      case VersionReq.PinnedHash(h) => pool.find(_.schemaHash == h)
      case VersionReq.Latest        => pool.toList.maxByOption(_.version)
      case VersionReq.Caret(v) =>
        pool
          .filter(rv => rv.version.major == v.major && SemVer.ordering.gteq(rv.version, v))
          .toList
          .maxByOption(_.version)
      case VersionReq.Tilde(v) =>
        pool
          .filter(rv =>
            rv.version.major == v.major && rv.version.minor == v.minor && SemVer.ordering.gteq(rv.version, v)
          )
          .toList
          .maxByOption(_.version)
    }
    out.toRight(RegistryError.Unresolvable(req))
  }
}

object VersionLineage {
  val empty: VersionLineage = VersionLineage(SortedMap.empty[SemVer, RegisteredVersion])

  /** Build from an initial version. */
  def of(rv: RegisteredVersion): VersionLineage = VersionLineage(SortedMap(rv.version -> rv))
}
