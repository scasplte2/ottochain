package xyz.kd5ujc.schema.registry

/** Failures from the registry's content-agnostic structural rules. */
sealed trait RegistryError

object RegistryError {
  final case class VersionExists(version: SemVer) extends RegistryError
  final case class NonMonotonic(attempted: SemVer, current: SemVer) extends RegistryError
  final case class VersionNotFound(version: SemVer) extends RegistryError
  final case class IllegalStatusTransition(from: RegistryStatus, to: RegistryStatus) extends RegistryError
  final case class Unresolvable(req: VersionReq) extends RegistryError
}
