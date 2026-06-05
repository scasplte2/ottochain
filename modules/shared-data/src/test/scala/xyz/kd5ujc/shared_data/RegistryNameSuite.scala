package xyz.kd5ujc.shared_data

import cats.effect.IO

import xyz.kd5ujc.schema.registry.{NameTld, RegistryName}

import weaver.SimpleIOSuite

object RegistryNameSuite extends SimpleIOSuite {

  test("accepts <labels>.<tld>; rejects a missing/invalid TLD and malformed labels") {
    IO.pure(
      expect(RegistryName.from("escrow.package").isRight) and
      expect(RegistryName.from("gov.threshold-dao.package").isRight) and
      expect(RegistryName.from("my-fiber.machine").isRight) and
      expect(RegistryName.from("oracle.script").isRight) and
      expect(RegistryName.from("escrow").isLeft) and // no TLD
      expect(RegistryName.from("escrow.acme").isLeft) and // "acme" is not a reserved TLD
      expect(RegistryName.from(".package").isLeft) and // empty labels
      expect(RegistryName.from("Escrow.package").isLeft) and // uppercase label
      expect(RegistryName.from("a..b.package").isLeft) and // empty label
      expect(RegistryName.from("-bad.package").isLeft) and // leading hyphen
      expect(RegistryName.from("bad-.package").isLeft) and // trailing hyphen
      expect(RegistryName.from("a_b.package").isLeft) and // underscore
      expect(RegistryName.from(("a" * 300) + ".package").isLeft) // too long / label > 63
    )
  }

  test("render includes the TLD; labels + tld are parsed; ordering is lexical by render") {
    val a = RegistryName.unsafe("alpha.package")
    val b = RegistryName.unsafe("beta.machine")
    IO.pure(
      expect(a.render == "alpha.package") and
      expect(a.tld == NameTld.Package) and
      expect(a.labels.value == "alpha") and
      expect(b.tld == NameTld.Machine) and
      expect(RegistryName.ordering.lt(a, b)) // "alpha.package" < "beta.machine"
    )
  }
}
