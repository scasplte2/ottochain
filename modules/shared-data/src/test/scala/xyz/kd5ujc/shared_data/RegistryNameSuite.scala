package xyz.kd5ujc.shared_data

import cats.effect.IO

import xyz.kd5ujc.schema.registry.RegistryName

import weaver.SimpleIOSuite

object RegistryNameSuite extends SimpleIOSuite {

  test("accepts dotted lowercase labels; rejects malformed names") {
    IO.pure(
      expect(RegistryName.from("escrow").isRight) and
      expect(RegistryName.from("gov.threshold-dao").isRight) and
      expect(RegistryName.from("escrow.acme.v1").isRight) and
      expect(RegistryName.from("").isLeft) and // empty
      expect(RegistryName.from("Escrow").isLeft) and // uppercase
      expect(RegistryName.from("a..b").isLeft) and // empty label
      expect(RegistryName.from("-bad").isLeft) and // leading hyphen
      expect(RegistryName.from("bad-").isLeft) and // trailing hyphen
      expect(RegistryName.from("a_b").isLeft) and // underscore
      expect(RegistryName.from("a" * 300).isLeft) // too long / label > 63
    )
  }

  test("render round-trips and ordering is lexical") {
    val a = RegistryName.unsafe("alpha")
    val b = RegistryName.unsafe("beta")
    IO.pure(
      expect(a.render == "alpha") and
      expect(RegistryName.ordering.lt(a, b))
    )
  }
}
