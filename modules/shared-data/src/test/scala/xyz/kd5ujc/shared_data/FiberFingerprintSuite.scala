package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import xyz.kd5ujc.schema.fiber.{FiberFingerprint, FiberKind}

import weaver.SimpleIOSuite

object FiberFingerprintSuite extends SimpleIOSuite {

  private val samples: List[UUID] = List(
    new UUID(0L, 0L),
    new UUID(-1L, -1L),
    new UUID(0x0123456789abcdefL, 0xfedcba9876543210L),
    new UUID(1L, 2L),
    UUID.fromString("607e22ae-1111-4222-8333-444455556666")
  )

  test("round-trips: decode(encode(uuid)) == uuid for all samples") {
    IO.pure(
      samples
        .map(u => expect(FiberFingerprint.decode(FiberFingerprint.encode(u)) == Right(u)))
        .reduce(_ and _)
    )
  }

  test("format: 9 dash-separated quintets, each 5 chars") {
    val parts = FiberFingerprint.encode(samples(2)).split('-')
    IO.pure(
      expect(parts.length == 9) and
      expect(parts.forall(_.length == 5))
    )
  }

  test("of appends the fiber-kind TLD and remains decodable") {
    val u = samples(2)
    val sm = FiberFingerprint.of(u, FiberKind.StateMachine)
    val sc = FiberFingerprint.of(u, FiberKind.Script)
    IO.pure(
      expect(sm.endsWith(".machine")) and
      expect(sc.endsWith(".script")) and
      expect(FiberFingerprint.decode(sm) == Right(u)) and
      expect(FiberFingerprint.decode(sc) == Right(u))
    )
  }

  test("deterministic: same uuid always encodes to the same fingerprint") {
    val u = samples(4)
    IO.pure(expect(FiberFingerprint.encode(u) == FiberFingerprint.encode(u)))
  }

  test("tampering a data quintet never silently round-trips to the original uuid") {
    val u = samples(2)
    val parts = FiberFingerprint.encode(u).split('-')
    val flipped =
      (if (parts(0).charAt(0) == 'b') 'd' else 'b').toString + parts(0).substring(1)
    val tampered = (flipped +: parts.tail).mkString("-")
    IO.pure(expect(FiberFingerprint.decode(tampered) != Right(u)))
  }

  test("wrong quintet count is rejected") {
    val parts = FiberFingerprint.encode(samples(2)).split('-')
    val tooFew = parts.dropRight(1).mkString("-")
    IO.pure(expect(FiberFingerprint.decode(tooFew).isLeft))
  }

  test("invalid alphabet is rejected") {
    // 'x' is not in the proquint consonant/vowel alphabet
    IO.pure(expect(FiberFingerprint.decode("xxxxx-babad-gutih-tugad-fadih-rinov-kanut-zalum-bavor").isLeft))
  }
}
