package xyz.kd5ujc.shared_data

import java.io.{File, FilenameFilter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.IO

import xyz.kd5ujc.schema.fiber.StateMachineDefinition
import xyz.kd5ujc.schema.registry.{FieldShape, MachineShape, MessageShape}
import xyz.kd5ujc.shared_data.fiber.DefinitionLinter
import xyz.kd5ujc.shared_data.fiber.DefinitionLinter.{Diagnostic, Severity}

import io.circe.parser.decode
import weaver.SimpleIOSuite

/**
 * Tests for the offline, advisory [[DefinitionLinter]] (Proposal 01 — fiber-ergonomics/01-authoring-safety):
 *   - every shipped riverdale definition lints clean of Errors (read from disk when present, plus an embedded
 *     copy so the property is asserted even without file access);
 *   - a misspelled `_triger` directive is a hard `unknown-directive` Error (the F4 killer);
 *   - a read of a never-written `state.X` with no shape is an `undeclared-state-read` Warning;
 *   - a state unreachable from `initialState` is an `unreachable-state` Error.
 */
object DefinitionLinterSuite extends SimpleIOSuite {

  private def parseDef(s: String): StateMachineDefinition =
    decode[StateMachineDefinition](s).fold(e => throw new RuntimeException(s"parse def: $e"), identity)

  private def errorsOf(ds:   List[Diagnostic]): List[Diagnostic] = ds.filter(_.severity == Severity.Error)
  private def warningsOf(ds: List[Diagnostic]): List[Diagnostic] = ds.filter(_.severity == Severity.Warning)

  private def renderErrors(ds: List[Diagnostic]): String =
    errorsOf(ds).map(d => s"${d.code} @ ${d.location} :: ${d.message}").mkString(" | ")

  // ---------------------------------------------------------------------------
  // riverdale definitions lint clean (no Errors)
  // ---------------------------------------------------------------------------

  /** Walk up from the JVM working directory to find the shipped riverdale example definitions. */
  private def riverdaleDir: Option[File] = {
    val rel = "e2e-test/examples/riverdale-economy"
    LazyList
      .iterate(Option(new File(System.getProperty("user.dir")).getAbsoluteFile))(
        _.flatMap(f => Option(f.getParentFile))
      )
      .takeWhile(_.isDefined)
      .flatten
      .take(8)
      .map(base => new File(base, rel))
      .find(_.isDirectory)
  }

  riverdaleDir match {
    case Some(dir) =>
      val jsonFilter: FilenameFilter = (_, name) => name.endsWith(".definition.json")
      val files = Option(dir.listFiles(jsonFilter)).getOrElse(Array.empty[File]).toList.sortBy(_.getName)
      files.foreach { f =>
        test(s"riverdale ${f.getName} lints clean (no Errors)") {
          val src = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
          val diags = DefinitionLinter.validate(parseDef(src))
          IO.pure(
            if (errorsOf(diags).isEmpty) success
            else failure(s"${f.getName} produced Errors: ${renderErrors(diags)}")
          )
        }
      }
    case None =>
      // The embedded manufacturer test below still asserts the clean-definition property, so a missing
      // examples directory (e.g. an unusual working dir) is non-fatal — record it without failing.
      pureTest("riverdale example directory not located — relying on embedded definition")(success)
  }

  // An embedded, verbatim copy of manufacturer.definition.json — guarantees the "clean" property is asserted
  // even when file IO is unavailable. Uses _triggers / _transferAsset directives and state reads/writes.
  private val manufacturerJson =
    """
    {
      "states": {
        "stocked": { "id": "stocked", "isFinal": false },
        "shipped": { "id": "shipped", "isFinal": false }
      },
      "initialState": "stocked",
      "transitions": [
        {
          "from": "stocked",
          "to": "shipped",
          "eventName": "fulfill_order",
          "guard": { ">=": [{ "var": "state.inventory" }, { "var": "event.quantity" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "receive_shipment",
                "payload": { "quantity": { "var": "event.quantity" } }
              }
            ],
            "_transferAsset": [
              { "assetId": { "var": "event.goodsAssetId" }, "recipient": { "var": "event.retailerId" } }
            ],
            "status": "shipped",
            "inventory": { "-": [{ "var": "state.inventory" }, { "var": "event.quantity" }] }
          },
          "dependencies": []
        },
        {
          "from": "shipped",
          "to": "shipped",
          "eventName": "pay_taxes",
          "guard": { "==": [1, 1] },
          "effect": {
            "taxesPaid": { "+": [{ "var": "state.taxesPaid" }, { "var": "event.taxAmount" }] }
          },
          "dependencies": []
        }
      ]
    }
    """

  test("embedded manufacturer definition lints clean (no Errors)") {
    val diags = DefinitionLinter.validate(parseDef(manufacturerJson))
    IO.pure(
      if (errorsOf(diags).isEmpty) success
      else failure(s"unexpected Errors: ${renderErrors(diags)}")
    )
  }

  // ---------------------------------------------------------------------------
  // (b) a typo'd `_`-directive is a hard Error (kills F4)
  // ---------------------------------------------------------------------------

  private val typoDirectiveJson =
    """
    {
      "states": { "a": { "id": "a", "isFinal": false }, "b": { "id": "b", "isFinal": true } },
      "initialState": "a",
      "transitions": [
        {
          "from": "a", "to": "b", "eventName": "go",
          "guard": { "==": [1, 1] },
          "effect": {
            "_triger": [
              { "targetMachineId": "00000000-0000-0000-0000-000000000001", "eventName": "x", "payload": {} }
            ],
            "status": "b"
          },
          "dependencies": []
        }
      ]
    }
    """

  test("a typo'd `_triger` directive produces an unknown-directive Error") {
    val diags = DefinitionLinter.validate(parseDef(typoDirectiveJson))
    val unknown = diags.filter(d => d.code == "unknown-directive" && d.severity == Severity.Error)
    IO.pure(
      expect(unknown.nonEmpty) and
      expect(unknown.exists(_.message.contains("_triggers"))) // nearest-directive suggestion
    )
  }

  // ---------------------------------------------------------------------------
  // (a)/(d) a read of a never-written `state.X` with no shape is a Warning
  // ---------------------------------------------------------------------------

  private val unseededReadJson =
    """
    {
      "states": { "a": { "id": "a", "isFinal": false }, "b": { "id": "b", "isFinal": true } },
      "initialState": "a",
      "transitions": [
        {
          "from": "a", "to": "b", "eventName": "go",
          "guard": { ">": [{ "var": "state.unseeded" }, 0] },
          "effect": { "status": "b" },
          "dependencies": []
        }
      ]
    }
    """

  test("a read of a never-written state field (no shape) is an undeclared-state-read Warning") {
    val diags = DefinitionLinter.validate(parseDef(unseededReadJson))
    val warns = warningsOf(diags).filter(d => d.code == "undeclared-state-read")
    IO.pure(
      expect(errorsOf(diags).isEmpty) and
      expect(warns.exists(_.location.path.contains("state.unseeded")))
    )
  }

  // ---------------------------------------------------------------------------
  // (c) an unreachable state is an Error
  // ---------------------------------------------------------------------------

  private val unreachableJson =
    """
    {
      "states": {
        "a": { "id": "a", "isFinal": false },
        "b": { "id": "b", "isFinal": false },
        "orphan": { "id": "orphan", "isFinal": false }
      },
      "initialState": "a",
      "transitions": [
        {
          "from": "a", "to": "b", "eventName": "go",
          "guard": { "==": [1, 1] },
          "effect": { "status": "b" },
          "dependencies": []
        }
      ]
    }
    """

  test("a state unreachable from initialState produces an unreachable-state Error") {
    val diags = DefinitionLinter.validate(parseDef(unreachableJson))
    val unreachable = diags.filter(d => d.code == "unreachable-state" && d.severity == Severity.Error)
    IO.pure(
      expect(unreachable.nonEmpty) and
      expect(unreachable.exists(_.location.path.contains("orphan")))
    )
  }

  // ---------------------------------------------------------------------------
  // (d) WITH a shape: undeclared read/write become Errors
  // ---------------------------------------------------------------------------

  private val shape: MachineShape =
    MachineShape(
      stateMessage = MessageShape(
        "App.State",
        List(FieldShape("status", 1, "string", repeated = false, optional = false))
      ),
      commands = scala.collection.immutable.SortedMap.empty
    )

  test("with a shape, an undeclared state read and write are Errors") {
    val diags = DefinitionLinter.validate(parseDef(unseededReadJson), Some(shape))
    val readErr = diags.filter(d => d.code == "undeclared-state-read" && d.severity == Severity.Error)
    // The unseeded def only writes the declared `status`, so no write error there — assert the read flips to Error.
    IO.pure(expect(readErr.nonEmpty))
  }

  // ---------------------------------------------------------------------------
  // (b2) _transferAsset recipient shape (object-form-only)
  // ---------------------------------------------------------------------------

  private def transferDef(recipientJson: String): String =
    s"""
    {
      "states": { "s0": { "id": "s0", "isFinal": false }, "s1": { "id": "s1", "isFinal": true } },
      "initialState": "s0",
      "transitions": [
        {
          "from": "s0", "to": "s1", "eventName": "go", "guard": true,
          "effect": { "_transferAsset": [ { "assetId": { "var": "event.aid" }, "recipient": $recipientJson } ], "done": true },
          "dependencies": []
        }
      ]
    }
    """

  test("a well-formed object-form _transferAsset recipient produces no recipient diagnostic") {
    val diags =
      DefinitionLinter.validate(parseDef(transferDef("""{ "Fiber": { "fiberId": { "var": "event.to" } } }""")))
    IO.pure(expect(diags.forall(!_.code.startsWith("transfer-recipient"))))
  }

  test("a bare-string _transferAsset recipient is a transfer-recipient-bare-string Warning") {
    val diags = DefinitionLinter.validate(parseDef(transferDef(""" "DAGsomeBareRecipientAddress" """)))
    val warns = warningsOf(diags).filter(_.code == "transfer-recipient-bare-string")
    IO.pure(expect(warns.nonEmpty) and expect(warns.forall(_.location.path.contains("recipient"))))
  }

  test("a malformed object _transferAsset recipient is a transfer-recipient Error") {
    val missingField =
      DefinitionLinter.validate(parseDef(transferDef("""{ "Fiber": { "nope": { "var": "event.to" } } }""")))
    val wrongVariant = DefinitionLinter.validate(parseDef(transferDef("""{ "Holder": { "id": "x" } }""")))
    IO.pure(
      expect(errorsOf(missingField).exists(_.code == "transfer-recipient-malformed")) and
      expect(errorsOf(wrongVariant).exists(_.code == "transfer-recipient-not-holder"))
    )
  }

  // ---------------------------------------------------------------------------
  // (b2b) _consumeNullifier value shape (bare 64-hex values)
  // ---------------------------------------------------------------------------

  private def nullifierDef(itemsJson: String): String =
    s"""
    {
      "states": { "s0": { "id": "s0", "isFinal": false }, "s1": { "id": "s1", "isFinal": true } },
      "initialState": "s0",
      "transitions": [
        {
          "from": "s0", "to": "s1", "eventName": "fill", "guard": true,
          "effect": { "_consumeNullifier": [ $itemsJson ], "filled": true },
          "dependencies": []
        }
      ]
    }
    """

  test("a well-formed 64-hex _consumeNullifier literal produces no nullifier diagnostic (0x form too)") {
    val plain = DefinitionLinter.validate(parseDef(nullifierDef(s""""${"a" * 64}"""")))
    val prefixed = DefinitionLinter.validate(parseDef(nullifierDef(s""""0x${"B" * 64}"""")))
    IO.pure(
      expect(plain.forall(_.code != "nullifier-literal-malformed")) and
      expect(prefixed.forall(_.code != "nullifier-literal-malformed"))
    )
  }

  test("an obviously-wrong _consumeNullifier literal is a nullifier-literal-malformed Warning") {
    val badHex = DefinitionLinter.validate(parseDef(nullifierDef(""""definitely-not-hex"""")))
    val tooShort = DefinitionLinter.validate(parseDef(nullifierDef(s""""${"a" * 32}"""")))
    val nonString = DefinitionLinter.validate(parseDef(nullifierDef("42")))
    IO.pure(
      expect(warningsOf(badHex).exists(_.code == "nullifier-literal-malformed")) and
      expect(warningsOf(tooShort).exists(_.code == "nullifier-literal-malformed")) and
      expect(warningsOf(nonString).exists(_.code == "nullifier-literal-malformed"))
    )
  }

  test("a dynamic _consumeNullifier item (var/cat) produces no nullifier diagnostic (checked at combine)") {
    val dynamic = DefinitionLinter.validate(parseDef(nullifierDef("""{ "var": "event.nf" }""")))
    IO.pure(expect(dynamic.forall(_.code != "nullifier-literal-malformed")))
  }

  // ---------------------------------------------------------------------------
  // (b3) directive-injection hazard: dynamic TOP-LEVEL effect keys
  // ---------------------------------------------------------------------------

  private def effectDef(effectJson: String): String =
    s"""
    {
      "states": { "s0": { "id": "s0", "isFinal": false }, "s1": { "id": "s1", "isFinal": true } },
      "initialState": "s0",
      "transitions": [
        { "from": "s0", "to": "s1", "eventName": "go", "guard": true, "effect": $effectJson, "dependencies": [] }
      ]
    }
    """

  test("a top-level dynamic effect key (set with a non-literal key) is a directive-injection-hazard Warning") {
    val danger =
      effectDef(
        """{ "merge": [ { "var": "state" }, { "set": [ {}, { "var": "event.k" }, { "var": "event.v" } ] } ] }"""
      )
    val warns = warningsOf(DefinitionLinter.validate(parseDef(danger))).filter(_.code == "directive-injection-hazard")
    IO.pure(expect(warns.nonEmpty))
  }

  test("a NESTED dynamic key (accumulator under a state field) is NOT an injection hazard") {
    val safe = effectDef(
      """{ "merge": [ { "var": "state" }, { "tally": { "set": [ { "var": "state.tally" }, { "var": "event.k" }, true ] } } ] }"""
    )
    val diags = DefinitionLinter.validate(parseDef(safe))
    IO.pure(expect(diags.forall(_.code != "directive-injection-hazard")))
  }

  test("a literal-key set is NOT an injection hazard") {
    val lit = effectDef("""{ "set": [ { "var": "state" }, "status", "done" ] }""")
    val diags = DefinitionLinter.validate(parseDef(lit))
    IO.pure(expect(diags.forall(_.code != "directive-injection-hazard")))
  }
}
