package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber.{FiberDirective, ReservedKeys}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Invariants over the fiber VOCABULARY single-sources, so the derived sets / tooling can never drift from the
 * runtime: [[FiberDirective]] is the one directive list (`ReservedKeys.directiveKeys` derives from it), and
 * [[AssetHolder.WireKeys]] is pinned to what the magnolia codec actually emits (so the DefinitionLinter's
 * recipient-shape check can't go stale).
 */
object FiberVocabularySuite extends SimpleIOSuite {

  pureTest("ReservedKeys.directiveKeys derives from FiberDirective (single source)") {
    expect(ReservedKeys.directiveKeys == FiberDirective.values.flatMap(_.keys).toSet) and
    expect(ReservedKeys.directiveKeys.forall(_.startsWith("_")))
  }

  test("AssetHolder.WireKeys match the magnolia codec output (drift guard)") {
    TestFixture.resource(Set(Bob)).use { fixture =>
      val fiberJson = (AssetHolder.Fiber(UUID.fromString("acc70000-0000-4000-8000-000000000001")): AssetHolder).asJson
      val walletJson = (AssetHolder.Wallet(fixture.registry.addresses(Bob)): AssetHolder).asJson

      def topKeys(j: io.circe.Json): Set[String] = j.asObject.map(_.keys.toSet).getOrElse(Set.empty)
      def innerKeys(j: io.circe.Json, variant: String): Set[String] =
        j.hcursor.downField(variant).keys.map(_.toSet).getOrElse(Set.empty)

      IO.pure(
        expect(topKeys(fiberJson) == Set(AssetHolder.WireKeys.FiberVariant)) and
        expect(topKeys(walletJson) == Set(AssetHolder.WireKeys.WalletVariant)) and
        expect(innerKeys(fiberJson, AssetHolder.WireKeys.FiberVariant) == Set(AssetHolder.WireKeys.FiberIdField)) and
        expect(innerKeys(walletJson, AssetHolder.WireKeys.WalletVariant) == Set(AssetHolder.WireKeys.AddressField))
      )
    }
  }
}
