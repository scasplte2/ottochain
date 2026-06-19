package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A single entry in a fiber's append-only dynamic-dependency ledger.
 *
 * Dynamic dependencies extend the STATIC, per-transition `Transition.dependencies` with a per-FIBER,
 * runtime-mutable set of fibers whose state is injected into `machines.<fiberId>.state` for EVERY
 * transition of this fiber. They are mutated only via the reserved `_addDependency` /
 * `_setDependencyActive` effect directives (guard-authorized, deterministic), enabling patterns like a
 * fiber binding to an identity-registry instance it learns about at runtime (per-actor cross-fiber
 * reads) without re-deploying its definition.
 *
 * The ledger is APPEND-ONLY with at most one entry per `fiberId`: a dependency is NEVER removed —
 * deactivation flips `active` to false while preserving the original `addedAt` — so the history is a
 * deterministic, auditable record and an inactive dep can be cheaply re-activated. The `machines`
 * context is built from the ACTIVE subset only; ExecutionLimits bounds both the active count and the
 * total ledger size for DoS safety.
 */
@derive(customizableEncoder, customizableDecoder)
final case class DynamicDependency(
  fiberId: UUID,
  active:  Boolean,
  addedAt: SnapshotOrdinal
)
