package xyz.kd5ujc.shared_data

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
 * The `_consumeNullifier` extractor boundary (protocol-nullifier-set.md): each array item is a bare nf VALUE
 * (a string literal or a sub-expression that evaluates to one) — no object wrapper, no domain field — and is
 * NORMALIZED through [[NullifierHex]] (strip optional `0x`, lowercase, require exactly 64 hex chars).
 * Asserts at [[EffectExtractor.extractNullifierConsumptions]] that:
 *   - a single literal and a list of literals parse into [[FiberEffect.NullifierConsumed]] in authored order,
 *   - an evaluated sub-expression (`{"var":..}` / `{"cat":[..]}`) resolves against the context (gas charged),
 *   - `0x`-prefixed and uppercase inputs NORMALIZE to the canonical 64-lowercase-hex form,
 *   - a malformed item (bad hex / wrong length / non-string) is REJECTED loudly ([[CombineRejected]]),
 *   - an absent directive is a no-op (no items, no error).
 */
object NullifierExtractorSuite extends SimpleIOSuite {

  import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._

  private val nfA = "a1" * 32 // 64 lowercase hex chars
  private val nfB = "0b" * 32

  // Drive EffectExtractor.extractNullifierConsumptions in the same FiberT MTL stack the engine uses.
  private def runExtract(
    effectResult: JsonLogicValue,
    ctx:          JsonLogicValue = MapValue(Map.empty)
  ): IO[(List[FiberEffect.NullifierConsumed], Long)] = {
    val prog: FiberT[IO, (List[FiberEffect.NullifierConsumed], Long)] =
      for {
        consumptions <- EffectExtractor.extractNullifierConsumptions[IO, FiberT[IO, *]](effectResult, ctx)
        gasUsed      <- ExecutionOps.getGasUsed[FiberT[IO, *]]
      } yield (consumptions, gasUsed)
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
  }

  private def consumeEffect(items: JsonLogicValue*): JsonLogicValue =
    MapValue(Map(ReservedKeys.CONSUME_NULLIFIER -> ArrayValue(items.toList)))

  private def isRejected(io: IO[(List[FiberEffect.NullifierConsumed], Long)]): IO[Boolean] =
    io.attempt.map(_.left.toOption.exists(_.isInstanceOf[CombineRejected]))

  test("a single 64-hex literal parses into NullifierConsumed") {
    runExtract(consumeEffect(StrValue(nfA))).map { case (consumptions, _) =>
      expect(consumptions == List(FiberEffect.NullifierConsumed(Hash(nfA))))
    }
  }

  test("a list of literals parses in authored order") {
    runExtract(consumeEffect(StrValue(nfA), StrValue(nfB))).map { case (consumptions, _) =>
      expect(
        consumptions == List(FiberEffect.NullifierConsumed(Hash(nfA)), FiberEffect.NullifierConsumed(Hash(nfB)))
      )
    }
  }

  test("an evaluated sub-expression resolves against the context (and charges gas)") {
    val ctx = MapValue(Map("nf" -> StrValue(nfA)))
    val item = MapValue(Map(ReservedKeys.VAR -> StrValue("nf")))
    runExtract(consumeEffect(item), ctx).map { case (consumptions, gasUsed) =>
      expect(consumptions == List(FiberEffect.NullifierConsumed(Hash(nfA)))) and
      expect(gasUsed > 0L)
    }
  }

  test("0x-prefixed and uppercase inputs normalize to canonical 64-lowercase-hex") {
    val upper = nfA.toUpperCase
    for {
      prefixed <- runExtract(consumeEffect(StrValue(s"0x$nfA"))).map(_._1)
      upperRes <- runExtract(consumeEffect(StrValue(upper))).map(_._1)
      both     <- runExtract(consumeEffect(StrValue(s"0X$upper"))).map(_._1)
    } yield expect(prefixed == List(FiberEffect.NullifierConsumed(Hash(nfA)))) and
    expect(upperRes == List(FiberEffect.NullifierConsumed(Hash(nfA)))) and
    expect(both == List(FiberEffect.NullifierConsumed(Hash(nfA))))
  }

  test("malformed items are REJECTED loudly (bad hex / wrong length / non-string)") {
    for {
      badHex      <- isRejected(runExtract(consumeEffect(StrValue("z" * 64))))
      tooShort    <- isRejected(runExtract(consumeEffect(StrValue("ab" * 16))))
      tooLong     <- isRejected(runExtract(consumeEffect(StrValue("ab" * 33))))
      nonString   <- isRejected(runExtract(consumeEffect(IntValue(BigInt(42)))))
      firstBadAll <- isRejected(runExtract(consumeEffect(StrValue("nope"), StrValue(nfA))))
    } yield expect(badHex) and expect(tooShort) and expect(tooLong) and expect(nonString) and expect(firstBadAll)
  }

  test("an absent directive is a no-op (no items, no error)") {
    runExtract(MapValue(Map("filled" -> BoolValue(true)))).map { case (consumptions, _) =>
      expect(consumptions.isEmpty)
    }
  }
}
