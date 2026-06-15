package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.registry.{RegistryEntry, RegistryName, RegistryTarget}

import weaver.SimpleIOSuite

/**
 * The committed-state projection of CalculatedState (Phase 1 of the committed-state migration). The
 * genuinely load-bearing logic is the registry key derivation: CommitKey segments are <= 64 chars
 * and lowercase, but a RegistryName renders up to 253 — and `entries` is TOTAL (no error channel),
 * so an over-long name must fall back to a hashed key rather than throw inside combine.
 */
object CommittedViewSuite extends SimpleIOSuite {

  private def entry(name: RegistryName): RegistryEntry =
    RegistryEntry(name, Set.empty, RegistryTarget.InstanceAlias(UUID.randomUUID()))

  private val escrow = RegistryName.unsafe("escrow.package")
  // render = 60 + 1 + 10 + 1 + 7 = 79 chars -> overflows a 64-char CommitKey segment
  private val longName = RegistryName.unsafe(("a" * 60) + "." + ("b" * 10) + ".package")
  private val rid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

  private val state = CalculatedState(
    stateMachines = SortedMap.empty,
    scripts = SortedMap.empty,
    registry = SortedMap(escrow -> entry(escrow), longName -> entry(longName)),
    reverseNames = SortedMap(rid -> escrow)
  )

  test("registry uses readable keys, an over-long name falls back to a hashed key, reverse keys by uuid") {
    val entries = CalculatedState.committedView.entries(state)
    val keys = entries.keySet.map(_.value)
    IO.pure(
      expect(keys.contains("registry/escrow.package")) and
      expect(keys.exists(k => k.startsWith("registry/h/") && k.stripPrefix("registry/h/").matches("[0-9a-f]{64}"))) and
      expect(keys.contains(s"reverse/$rid")) and
      // every populated field projected, nothing silently dropped
      expect(entries.size == state.registry.size + state.reverseNames.size) and
      // deterministic across calls
      expect(CalculatedState.committedView.entries(state) == entries)
    )
  }

  test("entries is total: a maximal-length registry name projects without throwing") {
    val maxName = RegistryName.unsafe((1 to 3).map(_ => "a" * 60).mkString(".") + ".package") // ~190 chars
    val s = CalculatedState(SortedMap.empty, SortedMap.empty, SortedMap(maxName -> entry(maxName)), SortedMap.empty)
    IO(CalculatedState.committedView.entries(s)).map(e => expect(e.size == 1))
  }

  test("genesis projects to an empty dictionary") {
    IO.pure(expect(CalculatedState.committedView.entries(CalculatedState.genesis).isEmpty))
  }
}
