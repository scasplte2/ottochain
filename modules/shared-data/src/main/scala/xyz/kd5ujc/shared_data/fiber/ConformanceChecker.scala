package xyz.kd5ujc.shared_data.fiber

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.registry._

/**
 * Runtime conformance gate (#33, opt-in). When a fiber is bound to a `strict` registered version, the
 * state it PRODUCES (at create, transition, or migration) must conform to that version's typed
 * [[MachineShape]] — otherwise the transaction is aborted, so a non-conforming state is never committed.
 *
 * This is the "assume + error-out" tier: it does not prove the logic conforms for all inputs (a static
 * property the chain cannot check cheaply), only that the actual produced state does — exactly how a VM
 * reverts on a bad op rather than statically proving the contract. It is SHALLOW by design, bounded by the
 * on-chain shape: top-level field names + immediate primitive types are checked; a message/enum-typed
 * field is accepted as any non-null value (its full shape lives in the off-chain descriptor). Non-strict
 * versions and ad-hoc (unbound) fibers skip the gate entirely — the experimentation path.
 *
 * See docs/proposals/trust-and-verification-handoff.md (Tier 2) and strong-typing-and-conformance.md §0.5.
 */
object ConformanceChecker {

  private val IntegerTypes =
    Set("int32", "int64", "uint32", "uint64", "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64")
  private val FloatTypes = Set("double", "float")

  /** Violations of `value` against `message` (empty == conforms): undeclared fields + type mismatches. */
  def check(message: MessageShape, value: JsonLogicValue): List[String] =
    value match {
      case MapValue(fields) =>
        val declared = message.fields.iterator.map(f => f.name -> f).toMap
        fields.toList.flatMap { case (key, v) =>
          declared.get(key) match {
            case None                                   => List(s"undeclared field '$key' (not in ${message.typeName})")
            case Some(field) if fieldConforms(field, v) => Nil
            case Some(field) =>
              val pfx = if (field.repeated) "repeated " else ""
              List(s"field '$key' expected $pfx${field.typeName} but got ${v.tag}")
          }
        }
      case other => List(s"state must be a map but was ${other.tag}")
    }

  /**
   * If `binding` resolves to a STRICT registered version, the produced `value` must conform to its
   * MachineShape's state message. Returns the violations (empty == conforms OR the gate is not applicable:
   * unbound fiber, version not found, or non-strict version).
   */
  def violationsFor(binding: Option[SchemaBinding], state: CalculatedState, value: JsonLogicValue): List[String] =
    strictVersion(binding, state).fold(List.empty[String]) { rv =>
      rv.shape match {
        case RegistryShape.Machine(machineShape) => check(machineShape.stateMessage, value)
        case _: RegistryShape.Script             => List.empty
      }
    }

  private def strictVersion(binding: Option[SchemaBinding], state: CalculatedState): Option[RegisteredVersion] =
    binding.flatMap { b =>
      state.registry
        .get(b.name)
        .map(_.target)
        .collect { case RegistryTarget.SchemaPackage(lineage) => lineage }
        .flatMap(_.versions.get(b.version))
        .filter(_.strict)
    }

  private def fieldConforms(field: FieldShape, value: JsonLogicValue): Boolean =
    if (field.repeated) value match { case NullValue | _: ArrayValue => true; case _ => false }
    else
      value match {
        case NullValue => true // unset / proto3 default
        case other     => scalarConforms(field.typeName, other)
      }

  private def scalarConforms(typeName: String, value: JsonLogicValue): Boolean =
    typeName match {
      case "string" | "bytes" => value match { case _: StrValue => true; case _ => false }
      case "bool"             => value match { case _: BoolValue => true; case _ => false }
      case t if IntegerTypes(t) || FloatTypes(t) =>
        value match { case _: IntValue | _: FloatValue => true; case _ => false }
      case _ => true // message / enum / well-known type -> shallow: accept any non-null value
    }
}
