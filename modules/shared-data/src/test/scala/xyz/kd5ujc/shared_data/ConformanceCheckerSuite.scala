package xyz.kd5ujc.shared_data

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.registry.{FieldShape, MessageShape}
import xyz.kd5ujc.shared_data.fiber.ConformanceChecker

import weaver.SimpleIOSuite

/**
 * Unit tests for the shallow runtime conformance gate (#33): produced state vs the on-chain SchemaShape.
 */
object ConformanceCheckerSuite extends SimpleIOSuite {

  private val msg = MessageShape(
    "App.State",
    List(
      FieldShape("balance", 1, "int64", repeated = false, optional = false),
      FieldShape("name", 2, "string", repeated = false, optional = false),
      FieldShape("active", 3, "bool", repeated = false, optional = false),
      FieldShape("ratio", 4, "double", repeated = false, optional = false),
      FieldShape("tags", 5, "string", repeated = true, optional = false),
      FieldShape(
        "meta",
        6,
        "App.Meta",
        repeated = false,
        optional = false
      ) // message type -> shallow, accepts any non-null value
    )
  )

  test("a fully-conforming map has no violations") {
    val v = MapValue(
      Map(
        "balance" -> IntValue(5),
        "name"    -> StrValue("x"),
        "active"  -> BoolValue(true),
        "ratio"   -> FloatValue(1.5),
        "tags"    -> ArrayValue(List(StrValue("a"), StrValue("b"))),
        "meta"    -> MapValue(Map("anything" -> IntValue(1)))
      )
    )
    IO.pure(expect(ConformanceChecker.check(msg, v).isEmpty))
  }

  test("an undeclared field is a violation") {
    val v = MapValue(Map("ghost" -> IntValue(1)))
    IO.pure(expect(ConformanceChecker.check(msg, v).exists(_.contains("undeclared"))))
  }

  test("a wrong primitive type is a violation") {
    IO.pure(
      expect(ConformanceChecker.check(msg, MapValue(Map("balance" -> StrValue("nope")))).nonEmpty) and
      expect(ConformanceChecker.check(msg, MapValue(Map("active" -> IntValue(1)))).nonEmpty) and
      expect(ConformanceChecker.check(msg, MapValue(Map("name" -> BoolValue(true)))).nonEmpty)
    )
  }

  test("a repeated field requires an array; a scalar is a violation") {
    IO.pure(
      expect(ConformanceChecker.check(msg, MapValue(Map("tags" -> StrValue("not-array")))).nonEmpty) and
      expect(ConformanceChecker.check(msg, MapValue(Map("tags" -> ArrayValue(List.empty)))).isEmpty)
    )
  }

  test("numeric fields accept both IntValue and FloatValue") {
    IO.pure(
      expect(ConformanceChecker.check(msg, MapValue(Map("balance" -> FloatValue(3.0)))).isEmpty) and
      expect(ConformanceChecker.check(msg, MapValue(Map("ratio" -> IntValue(2)))).isEmpty)
    )
  }

  test("absent fields are allowed (proto3 defaults); explicit null conforms") {
    IO.pure(
      expect(ConformanceChecker.check(msg, MapValue(Map.empty[String, JsonLogicValue])).isEmpty) and
      expect(ConformanceChecker.check(msg, MapValue(Map("balance" -> NullValue))).isEmpty)
    )
  }

  test("a non-map state is a violation") {
    IO.pure(expect(ConformanceChecker.check(msg, StrValue("x")).nonEmpty))
  }
}
