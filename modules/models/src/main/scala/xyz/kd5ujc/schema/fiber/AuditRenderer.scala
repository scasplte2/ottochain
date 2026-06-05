package xyz.kd5ujc.schema.fiber

import java.util.UUID

import cats.syntax.show._

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.registry.SchemaBinding

/**
 * Human-readable rendering of the on-chain audit trail (the [[FiberLogEntry]] log in
 * `OnChain.latestLogs`). Every fiber is rendered by its deterministic, offline, checksummed proquint
 * [[FiberFingerprint]] — every fiber has one for free, so the trail is legible with no registration. Once
 * the name registry (#29) lands, a chosen nickname is prepended (`nickname (fingerprint)`, see
 * docs/proposals/naming-and-fingerprints.md §4); until then the fingerprint stands alone.
 *
 * Pure + reproducible: anyone (human, agent, light client) can regenerate these lines from the log + the
 * UUIDs, and the fingerprint round-trips back to the fiber UUID via [[FiberFingerprint.decode]].
 */
object AuditRenderer {

  /** Render an ordered list of log entries (e.g. one fiber's `OnChain.latestLogs` slice). */
  def renderAll(entries: List[FiberLogEntry]): List[String] = entries.map(render)

  /** A single audit line for one log entry. */
  def render(entry: FiberLogEntry): String = entry match {
    case r: FiberLogEntry.CreationReceipt =>
      val bound = r.schemaBinding.fold("")(b => s", bound to ${binding(b)}")
      val parent = r.parentFiberId.fold("")(p => s", child of ${machine(p)}")
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId)} created in state '${r.initialState.value}'$bound$parent"

    case r: FiberLogEntry.UpgradeReceipt =>
      val from = r.fromBinding.fold("(unbound)")(binding)
      val migrated = if (r.migrated) ", state migrated" else ""
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId)} upgraded $from -> ${binding(r.toBinding)}$migrated (gas ${r.gasUsed})"

    case r: FiberLogEntry.EventReceipt =>
      val outcome =
        if (r.success) s"${r.fromState.value} -> ${r.toState.value}"
        else s"FAILED${r.errorMessage.fold("")(e => s": $e")}"
      val triggers = if (r.triggersFired > 0) s", ${r.triggersFired} trigger(s)" else ""
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId)} '${r.eventName}' $outcome (gas ${r.gasUsed}$triggers)"

    case i: FiberLogEntry.OracleInvocation =>
      s"[ord ${ord(i.invokedAt)}] ${script(i.fiberId)} .${i.method}() by ${i.invokedBy.show} (gas ${i.gasUsed})"
  }

  private def machine(id: UUID): String = FiberFingerprint.of(id, FiberKind.StateMachine)
  private def script(id:  UUID): String = FiberFingerprint.of(id, FiberKind.Script)
  private def binding(b:  SchemaBinding): String = s"${b.name.render}@${b.version.render}"
  private def ord(o:      SnapshotOrdinal): String = o.value.value.toString
}
