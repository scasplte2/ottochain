package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import scala.util.Try

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber.{ReservedKeys, Transition}

/**
 * F6 (03-cross-fiber-and-authorization.md §2, option a) — AUTO-DECLARE cross-fiber READ dependencies.
 *
 * A guard/effect that statically reads `{"var":"machines.<uuid>.…"}` with a LITERAL `<uuid>` declares the
 * cross-fiber read in the same breath as performing it; the author should not also have to hand-maintain the
 * matching `Transition.dependencies` entry (the silent-`null` trap). This scanner walks a transition's guard +
 * effect AST (the same recursion shape as `FiberRules.L1.extractMapKeys` / `CommonRules.checkExpressionDepth`)
 * and returns every literally-referenced `machines.<uuid>` id.
 *
 * SCOPE (rule #1): the result augments ONLY the RUNTIME dependency set handed to `ContextProvider.buildContext`
 * (`FiberEvaluator.tryTransitions`). It MUST NEVER be written back into the signed, hash-pinned
 * `Transition.dependencies` field — that would diverge the canonical. The bound `buildMachinesContext` relies
 * on is preserved: the auto-added set is exactly the statically-referenced ids, finite and parse-time-known.
 *
 * A COMPUTED target (`{"var":"event.target"}`) cannot be resolved statically and is intentionally NOT covered;
 * that residue still needs an explicit static `dependencies` entry or the runtime `_addDependency` path.
 */
object StaticDependencyScan {

  /** Every literal `machines.<uuid>` id referenced by this transition's guard or effect expressions. */
  def staticMachineRefs(transition: Transition): Set[UUID] =
    collect(transition.guard) ++ collect(transition.effect)

  private def collect(expr: JsonLogicExpression): Set[UUID] =
    expr match {
      case MapExpression(map)              => map.values.flatMap(collect).toSet
      case ApplyExpression(_, args)        => args.flatMap(collect).toSet
      case ArrayExpression(items)          => items.flatMap(collect).toSet
      case VarExpression(Left(path), _)    => machineRefFromPath(path).toSet
      case VarExpression(Right(nested), _) => collect(nested)
      case _                               => Set.empty
    }

  /** Parse `machines.<uuid>[.…]` → the `<uuid>`; any non-`machines` path or non-UUID segment ⇒ None. */
  private def machineRefFromPath(path: String): Option[UUID] =
    path.split('.').toList match {
      case head :: id :: _ if head == ReservedKeys.MACHINES => Try(UUID.fromString(id)).toOption
      case _                                                => None
    }
}
