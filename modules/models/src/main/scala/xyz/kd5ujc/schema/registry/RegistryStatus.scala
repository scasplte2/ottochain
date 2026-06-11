package xyz.kd5ujc.schema.registry

import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/**
 * Lifecycle status of a registered version (npm/Cargo-style; see naming/schema RFCs).
 *
 *  - Active     — selectable + recommended.
 *  - Deprecated — still resolvable + runnable, flagged, discouraged for new instances (reversible).
 *  - Yanked     — excluded from NEW resolutions; existing pinned fibers keep running. Terminal.
 *
 * Status changes never affect a running fiber (it pinned its version at creation); they only steer
 * future `Latest`/range resolutions.
 */
sealed trait RegistryStatus extends EnumEntry with Uppercase

object RegistryStatus extends Enum[RegistryStatus] with CirceEnum[RegistryStatus] {
  val values: IndexedSeq[RegistryStatus] = findValues

  case object Active extends RegistryStatus
  case object Deprecated extends RegistryStatus
  case object Yanked extends RegistryStatus

  /** Legal transitions: Active↔Deprecated reversible; either → Yanked; Yanked is terminal. Same→same is a no-op. */
  def canTransition(from: RegistryStatus, to: RegistryStatus): Boolean =
    (from, to) match {
      case (a, b) if a == b                                                                      => true
      case (Active, Deprecated) | (Active, Yanked) | (Deprecated, Active) | (Deprecated, Yanked) => true
      case _                                                                                     => false
    }
}
