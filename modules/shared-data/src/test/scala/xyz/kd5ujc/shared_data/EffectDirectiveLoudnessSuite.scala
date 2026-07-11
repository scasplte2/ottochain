package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor
import xyz.kd5ujc.shared_data.lifecycle.combine.CombineRejected

import weaver.SimpleIOSuite

/**
 * L5 (audit 2026-07-07): a MALFORMED reserved directive is LOUD, not silently dropped. `_triggers`,
 * `_scriptCall`, `_addDependency`/`_setDependencyActive`, and `_emit` now raise a graceful [[CombineRejected]]
 * on a present-but-malformed item (missing required field / non-UUID / wrong type), the SAME mode
 * `_transferAsset` already used ([[AssetTransferRecipientObjectFormSuite]]). An ABSENT directive stays a no-op
 * (empty array ⇒ nothing to reject); only a PRESENT-but-malformed item rejects. Asserts at the
 * [[EffectExtractor]] boundary, in the same FiberT MTL stack the engine uses.
 */
object EffectDirectiveLoudnessSuite extends SimpleIOSuite {

  import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._

  private val src: UUID = new UUID(0L, 1L)
  private val target: UUID = new UUID(0L, 2L)

  private def run[A](prog: FiberT[IO, A]): IO[A] =
    prog
      .run(
        FiberContext(
          SnapshotOrdinal.MinValue,
          Hash.empty,
          io.constellationnetwork.schema.epoch
            .EpochProgress(eu.timepit.refined.types.numeric.NonNegLong.unsafeFrom(0L)),
          ExecutionLimits(),
          io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig.Default,
          FiberGasConfig.Default
        )
      )
      .runA(ExecutionState.initial)

  private def rejected[A](io: IO[A]): IO[Boolean] =
    io.attempt.map(_.left.toOption.exists(_.isInstanceOf[CombineRejected]))

  private def arr(items: JsonLogicValue*): JsonLogicValue = ArrayValue(items.toList)

  // ── _triggers ─────────────────────────────────────────────────────────────────────────────────────

  private def triggersEffect(item: JsonLogicValue): JsonLogicValue =
    MapValue(Map(ReservedKeys.TRIGGERS -> arr(item)))

  private def runTriggers(effect: JsonLogicValue): IO[List[FiberTrigger]] =
    run(EffectExtractor.extractTriggerEvents[IO, FiberT[IO, *]](effect, MapValue(Map.empty), src))

  test("_triggers: a well-formed item parses; an absent directive is a no-op") {
    val good = triggersEffect(
      MapValue(
        Map(
          ReservedKeys.TARGET_MACHINE_ID -> StrValue(target.toString),
          ReservedKeys.EVENT_NAME        -> StrValue("go"),
          ReservedKeys.PAYLOAD           -> MapValue(Map.empty)
        )
      )
    )
    for {
      parsed <- runTriggers(good)
      absent <- runTriggers(MapValue(Map.empty))
    } yield expect(parsed.map(_.targetFiberId) == List(target)) and expect(absent.isEmpty)
  }

  test("_triggers: a non-UUID targetMachineId is REJECTED (not silently dropped)") {
    val bad = triggersEffect(
      MapValue(
        Map(
          ReservedKeys.TARGET_MACHINE_ID -> StrValue("not-a-uuid"),
          ReservedKeys.EVENT_NAME        -> StrValue("go"),
          ReservedKeys.PAYLOAD           -> MapValue(Map.empty)
        )
      )
    )
    rejected(runTriggers(bad)).map(expect(_))
  }

  test("_triggers: a missing eventName is REJECTED") {
    val bad = triggersEffect(
      MapValue(
        Map(ReservedKeys.TARGET_MACHINE_ID -> StrValue(target.toString), ReservedKeys.PAYLOAD -> MapValue(Map.empty))
      )
    )
    rejected(runTriggers(bad)).map(expect(_))
  }

  // ── _scriptCall ───────────────────────────────────────────────────────────────────────────────────

  private def runScriptCall(effect: JsonLogicValue): IO[Option[FiberTrigger]] =
    run(EffectExtractor.extractScriptCall[IO, FiberT[IO, *]](effect, MapValue(Map.empty), src))

  test("_scriptCall: absent ⇒ None; present-but-missing-method ⇒ REJECTED") {
    val bad = MapValue(
      Map(
        ReservedKeys.SCRIPT_CALL -> MapValue(
          Map(ReservedKeys.FIBER_ID -> StrValue(target.toString), ReservedKeys.ARGS -> MapValue(Map.empty))
        )
      )
    )
    for {
      absent <- runScriptCall(MapValue(Map.empty))
      rejBad <- rejected(runScriptCall(bad))
    } yield expect(absent.isEmpty) and expect(rejBad)
  }

  // ── _addDependency / _setDependencyActive ───────────────────────────────────────────────────────────

  private def runDeps(effect: JsonLogicValue): IO[List[FiberEffect.DependencyMutated]] =
    run(EffectExtractor.extractDependencyMutations[IO, FiberT[IO, *]](effect, MapValue(Map.empty)))

  test("_addDependency: a non-UUID fiberId is REJECTED") {
    val bad =
      MapValue(Map(ReservedKeys.ADD_DEPENDENCY -> arr(MapValue(Map(ReservedKeys.FIBER_ID -> StrValue("nope"))))))
    rejected(runDeps(bad)).map(expect(_))
  }

  test("_setDependencyActive: a missing/non-boolean active is REJECTED; a well-formed _addDependency parses") {
    val badActive = MapValue(
      Map(ReservedKeys.SET_DEPENDENCY_ACTIVE -> arr(MapValue(Map(ReservedKeys.FIBER_ID -> StrValue(target.toString)))))
    )
    val good = MapValue(
      Map(ReservedKeys.ADD_DEPENDENCY -> arr(MapValue(Map(ReservedKeys.FIBER_ID -> StrValue(target.toString)))))
    )
    for {
      rej    <- rejected(runDeps(badActive))
      parsed <- runDeps(good)
    } yield expect(rej) and expect(parsed == List(FiberEffect.DependencyMutated(target, active = true)))
  }

  // ── _emit ───────────────────────────────────────────────────────────────────────────────────────────

  private def runEmit(effect: JsonLogicValue): IO[List[EmittedEvent]] =
    run(EffectExtractor.extractEmittedEvents[IO, FiberT[IO, *]](effect, src))

  test("_emit: a missing name is REJECTED; a well-formed event parses") {
    val bad = MapValue(Map(ReservedKeys.EMIT -> arr(MapValue(Map(ReservedKeys.DATA -> StrValue("x"))))))
    val good = MapValue(
      Map(
        ReservedKeys.EMIT -> arr(
          MapValue(Map(ReservedKeys.NAME -> StrValue("evt"), ReservedKeys.DATA -> StrValue("x")))
        )
      )
    )
    for {
      rej    <- rejected(runEmit(bad))
      parsed <- runEmit(good)
    } yield expect(rej) and expect(parsed.map(_.name) == List("evt"))
  }
}
