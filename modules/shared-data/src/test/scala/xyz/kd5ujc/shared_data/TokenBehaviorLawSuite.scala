package xyz.kd5ujc.shared_data

import cats.kernel.laws.discipline.{BoundedSemilatticeTests, EqTests, PartialOrderTests}

import xyz.kd5ujc.schema.asset.TokenBehavior

import org.scalacheck.{Arbitrary, Cogen, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

/** Shared generator: every TokenBehavior is some point in the 5-bit lattice (0..31). */
trait TokenBehaviorArbitrary {

  implicit val arbTokenBehavior: Arbitrary[TokenBehavior] =
    Arbitrary(Gen.choose(0, 31).map(TokenBehavior.fromBits))

  // cats-laws' Eq/PartialOrder ruleSets need Arbitrary[A => A], which ScalaCheck derives from a Cogen[A].
  implicit val cogenTokenBehavior: Cogen[TokenBehavior] =
    Cogen[Int].contramap(_.bits)
}

/**
 * (a) cats-laws discipline suite — Eq, PartialOrder, and BoundedSemilattice laws for [[TokenBehavior]].
 * Proves the lattice algebra (associativity/commutativity/idempotence of meet, Top as identity, and that
 * the derived PartialOrder is a lawful partial order consistent with Eq).
 */
object TokenBehaviorLawSuite extends FunSuite with Discipline with TokenBehaviorArbitrary {
  checkAll("TokenBehavior.eq", EqTests[TokenBehavior].eqv)
  checkAll("TokenBehavior.partialOrder", PartialOrderTests[TokenBehavior].partialOrder)
  checkAll("TokenBehavior.boundedSemilattice", BoundedSemilatticeTests[TokenBehavior].boundedSemilattice)
}
