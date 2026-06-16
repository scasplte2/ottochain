package xyz.kd5ujc.shared_data

import xyz.kd5ujc.schema.asset.TokenBehavior

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/**
 * (b) Property + concrete suite — the assertions cats-laws cannot express: the bit packing round-trip, the
 * GLB characterisation of [[TokenBehavior.meet]], Top/Bottom identity/absorption, the [[TokenBehavior.foldMeet]]
 * aggregator, the corrected Soulbound-composition counterexample, and preset bit sanity.
 */
object TokenBehaviorPropertySuite extends SimpleIOSuite with Checkers with TokenBehaviorArbitrary {

  // --- bits round-trip ----------------------------------------------------------------------------------

  test("bits round-trip: fromBits(n).bits == n for n in 0..31") {
    forall(Gen.choose(0, 31))(n => expect(TokenBehavior.fromBits(n).bits == n))
  }

  test("bits round-trip: fromBits(b.bits) == b for all behaviors") {
    forall((b: TokenBehavior) => expect(TokenBehavior.fromBits(b.bits) == b))
  }

  // --- GLB consistency (the formalism correction) -------------------------------------------------------

  test("meet is a lower bound: a.meet(b) <= a and <= b") {
    forall { (a: TokenBehavior, b: TokenBehavior) =>
      val ab = a meet b
      expect(ab <= a) && expect(ab <= b)
    }
  }

  test("meet is the GREATEST lower bound: (c <= a && c <= b) => c <= a.meet(b)") {
    forall { (a: TokenBehavior, b: TokenBehavior, c: TokenBehavior) =>
      val premise = (c <= a) && (c <= b)
      expect(!premise || (c <= (a meet b)))
    }
  }

  // --- Top / Bottom -------------------------------------------------------------------------------------

  test("Top is the meet identity, Bottom is absorbing") {
    forall { (a: TokenBehavior) =>
      expect((a meet TokenBehavior.Top) == a) &&
      expect((a meet TokenBehavior.Bottom) == TokenBehavior.Bottom)
    }
  }

  pureTest("Top == Fungible, Top.bits == 28, Bottom.bits == 3") {
    expect(TokenBehavior.Top == TokenBehavior.Fungible) &&
    expect(TokenBehavior.Top.bits == 28) &&
    expect(TokenBehavior.Bottom.bits == 3)
  }

  // --- foldMeet -----------------------------------------------------------------------------------------

  pureTest("foldMeet(Nil) == Top") {
    expect(TokenBehavior.foldMeet(Nil) == TokenBehavior.Top)
  }

  test("foldMeet(List(a)) == a") {
    forall((a: TokenBehavior) => expect(TokenBehavior.foldMeet(List(a)) == a))
  }

  test("foldMeet(List(a,b)) == a.meet(b)") {
    forall { (a: TokenBehavior, b: TokenBehavior) =>
      expect(TokenBehavior.foldMeet(List(a, b)) == (a meet b))
    }
  }

  // --- corrected counterexample (concrete) --------------------------------------------------------------

  pureTest("Soulbound.meet(GovernedFungible): forces T/S/C off but ACQUIRES G") {
    val r = TokenBehavior.Soulbound.meet(TokenBehavior.GovernedFungible)
    expect(r == TokenBehavior.fromBits(1)) &&
    expect(r != TokenBehavior.Soulbound) &&
    expect(r.governable) &&
    expect(!r.transferable) &&
    expect(!r.splittable) &&
    expect(!r.combinable)
  }

  // --- preset bits sanity -------------------------------------------------------------------------------

  pureTest("preset bits sanity") {
    expect(TokenBehavior.Soulbound.bits == 0) &&
    expect(TokenBehavior.NFT.bits == 16) &&
    expect(TokenBehavior.Ticket.bits == 18) &&
    expect(TokenBehavior.Fungible.bits == 28) &&
    expect(TokenBehavior.GovernedFungible.bits == 29) &&
    expect(TokenBehavior.FullFeatured.bits == 31)
  }
}
