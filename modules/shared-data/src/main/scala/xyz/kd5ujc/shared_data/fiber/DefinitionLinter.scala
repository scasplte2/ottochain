package xyz.kd5ujc.shared_data.fiber

import cats.Id

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber.{FiberContextRoot, ReservedKeys, StateId, StateMachineDefinition, Transition}
import xyz.kd5ujc.schema.registry.MachineShape
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.{CommonRules, FiberRules}

/**
 * Offline, PURE, ADVISORY-ONLY fiber-definition validator (Proposal 01 — fiber-ergonomics/01-authoring-safety).
 *
 * Resolves every `{"var":…}` path against the context roots the engine actually injects, rejects misspelled
 * `_`-directive keys (the F4 killer), checks state reachability, checks internal/shape conformance of state
 * reads/writes, and surfaces the existing expression-depth cap — all from the definition alone, BEFORE the
 * message is signed. Returns structured, source-located [[Diagnostic]]s (empty == clean).
 *
 * This is tooling, NOT a consensus surface. It MUST NEVER be called from `validateSignedUpdate` or any
 * combiner (CLAUDE.md rules #2/#3). It reads its vocabulary — the directive set and the context roots — from
 * [[ReservedKeys]] (the same source `EffectExtractor`/`ContextProvider` dispatch on) so it can never disagree
 * with the runtime about which `_`-keys are real or which roots exist.
 */
object DefinitionLinter {

  sealed trait Severity

  object Severity {
    case object Error extends Severity
    case object Warning extends Severity
    case object Info extends Severity
  }

  /**
   * Where a diagnostic points. `transitionIndex` is the 0-based index into `definition.transitions` (None for
   * definition-level diagnostics like reachability); `field` is "guard" | "effect" | "states" | "definition";
   * `path` is the offending var-path or map key when applicable.
   */
  final case class Location(
    transitionIndex: Option[Int] = None,
    field:           String,
    path:            Option[String] = None
  )

  final case class Diagnostic(
    severity: Severity,
    code:     String,
    message:  String,
    location: Location
  )

  // === Vocabulary, read from ReservedKeys (never hardcoded) ===

  /**
   * The known effect-output directives, dispatched on in `EffectExtractor` (`_triggers`/`_spawn`/`_scriptCall`/
   * `_emit`/`_transferAsset`/`_addDependency`/`_setDependencyActive`). Derived from [[FiberDirective]] (the
   * single source of truth) via `ReservedKeys.directiveKeys`, so it can NEVER drift from the runtime's
   * directive set — adding a directive is one `FiberDirective` case and every consumer here updates.
   */
  private val directives: Set[String] = ReservedKeys.directiveKeys

  /**
   * `_`-prefixed keys that are RECOGNIZED (so they are never mis-flagged as a typo), even though they are not
   * effect directives: `_state`/`_result` (script return convention) and `_policy` (the cross-fiber policy
   * projection). Anything `_`-prefixed outside this set in an effect can only be a typo or an unimplemented
   * directive — i.e. an Error.
   */
  private val recognizedInternalKeys: Set[String] = ReservedKeys.recognizedInternalKeys

  /**
   * The first-segment roots the engine injects into a state-machine transition context, taken verbatim from
   * `ContextProvider.buildStateMachineContext`. A `{"var":"…"}` whose first segment is not one of these can
   * only ever resolve to `null`.
   */
  private val knownRoots: Set[String] = FiberContextRoot.keys

  /** Directive names with the leading underscore stripped — the shape a "dropped-underscore" typo takes. */
  private val directivesNoUnderscore: Set[String] = directives.map(_.stripPrefix("_"))

  /**
   * Validate a definition. `shape` is an OPTIONAL typed projection: with it, undeclared state/event reads and
   * undeclared writes become Errors; without it, the validator degrades to internal consistency (a field
   * written by any transition is treated as a known state field for read checks).
   *
   * @return diagnostics, ordered; empty means clean. ADVISORY only — the caller decides whether to block.
   */
  def validate(
    definition: StateMachineDefinition,
    shape:      Option[MachineShape] = None
  ): List[Diagnostic] = {
    // Fields written by ANY transition's effect (internal-consistency knowledge for read checks).
    val writtenFields: Set[String] =
      definition.transitions.flatMap(t => effectOutputKeys(t.effect).filterNot(ReservedKeys.isInternal)).toSet

    val declaredStateFields: Set[String] =
      shape.map(_.stateMessage.fields.map(_.name).toSet).getOrElse(Set.empty)

    val perTransition = definition.transitions.zipWithIndex.flatMap { case (t, idx) =>
      checkVarPaths(t, idx, writtenFields, declaredStateFields, shape) ++
      checkDirectives(t, idx) ++
      checkTransferRecipients(t, idx) ++
      checkInjectionHazard(t, idx) ++
      checkWrites(t, idx, shape)
    }

    perTransition ++ checkReachability(definition) ++ checkDepth(definition)
  }

  // ==========================================================================
  // (a) var-path resolution + (d) read conformance
  // ==========================================================================

  private def checkVarPaths(
    t:                   Transition,
    idx:                 Int,
    writtenFields:       Set[String],
    declaredStateFields: Set[String],
    shape:               Option[MachineShape]
  ): List[Diagnostic] = {
    def forField(field: String, expr: JsonLogicExpression): List[Diagnostic] =
      collectVarPaths(expr).distinct.flatMap { path =>
        resolvePath(path, field, idx, t, writtenFields, declaredStateFields, shape)
      }

    forField("guard", t.guard) ++ forField("effect", t.effect)
  }

  private def resolvePath(
    path:                String,
    field:               String,
    idx:                 Int,
    t:                   Transition,
    writtenFields:       Set[String],
    declaredStateFields: Set[String],
    shape:               Option[MachineShape]
  ): List[Diagnostic] = {
    val loc = Location(Some(idx), field, Some(path))
    val segments = path.split('.').toList
    segments match {
      case Nil | "" :: _ =>
        // empty path == whole-context identity read; nothing to resolve
        Nil

      case root :: _ if !knownRoots.contains(root) =>
        List(
          Diagnostic(
            Severity.Error,
            "unknown-root",
            s"""{"var":"$path"} — unknown context root "$root"; the engine injects no such root, so this """ +
            s"""read can only resolve to null (known roots: ${knownRoots.toList.sorted.mkString(", ")})""",
            loc
          )
        )

      case root :: sub :: _ if root == ReservedKeys.STATE =>
        if (declaredStateFields.contains(sub) || writtenFields.contains(sub)) Nil
        else if (shape.isDefined)
          List(
            Diagnostic(
              Severity.Error,
              "undeclared-state-read",
              s"""{"var":"$path"} — "$sub" is not a field of the declared state-shape""",
              loc
            )
          )
        else
          List(
            Diagnostic(
              Severity.Warning,
              "undeclared-state-read",
              s"""{"var":"$path"} — "$sub" is never written by any transition (reads null -> 0/""/[])""",
              loc
            )
          )

      case root :: sub :: _ if root == ReservedKeys.EVENT =>
        // Event subfields are only checkable against a command shape; without one, accept (always clean).
        shape.flatMap(_.commands.get(t.eventName)) match {
          case Some(cmd) if !cmd.fields.exists(_.name == sub) =>
            List(
              Diagnostic(
                Severity.Error,
                "undeclared-event-read",
                s"""{"var":"$path"} — "$sub" is not a field of the "${t.eventName}" command shape""",
                loc
              )
            )
          case _ => Nil
        }

      case root :: depId :: _ if root == ReservedKeys.MACHINES =>
        if (t.dependencies.map(_.toString).contains(depId)) Nil
        else
          List(
            Diagnostic(
              Severity.Warning,
              "undeclared-dep-read",
              s"""{"var":"$path"} — "$depId" is not in this transition's declared dependencies """ +
              s"(machines.<id> resolves to null unless <id> is a declared dependency)",
              loc
            )
          )

      case _ =>
        // Known root used bare (e.g. machineId, $ordinal) or a root whose sub-tree we do not model
        // (parent/children/scripts/heldAssets) — accept.
        Nil
    }
  }

  // ==========================================================================
  // (b) directive-key spelling (the F4 killer)
  // ==========================================================================

  private def checkDirectives(t: Transition, idx: Int): List[Diagnostic] = {
    // Unknown `_`-directive: any internal key in the effect tree that is not a recognized reserved key.
    val unknownDirectives =
      collectAllMapKeys(t.effect).distinct
        .filter(k => ReservedKeys.isInternal(k) && !recognizedInternalKeys.contains(k))
        .map { key =>
          val suggestion = nearest(key.stripPrefix("_"), directivesNoUnderscore)
            .map(s => s""" (did you mean "_$s"?)""")
            .getOrElse("")
          Diagnostic(
            Severity.Error,
            "unknown-directive",
            s"""effect key "$key" is not a known directive$suggestion""",
            Location(Some(idx), "effect", Some(key))
          )
        }

    // Dropped-underscore: a NON-internal effect-output key one edit from a directive — becomes a junk state
    // field, and the directive silently never fires.
    val droppedUnderscore =
      effectOutputKeys(t.effect)
        .filterNot(ReservedKeys.isInternal)
        .toList
        .filter(k => directivesNoUnderscore.exists(d => editDistance(k, d) <= 1))
        .map { key =>
          val suggestion = nearest(key, directivesNoUnderscore).map(s => s""" (did you mean "_$s"?)""").getOrElse("")
          Diagnostic(
            Severity.Warning,
            "likely-dropped-underscore",
            s"""effect key "$key" looks like a directive missing its underscore$suggestion; """ +
            s"it will merge into state instead of firing",
            Location(Some(idx), "effect", Some(key))
          )
        }

    unknownDirectives ++ droppedUnderscore
  }

  // ==========================================================================
  // (b2) `_transferAsset` recipient shape (object-form-only)
  // ==========================================================================

  /**
   * `_transferAsset`'s `recipient` is the canonical AssetHolder OBJECT form ONLY
   * (`{"Fiber":{"fiberId":..}}` / `{"Wallet":{"address":..}}`); the chain raises `CombineRejected` on anything
   * else (`EffectExtractor.parseAssetTransfer`). This is the offline shift-left for that — a STATIC check of the
   * recipient SHAPE (the resolved value is still checked at combine). Advisory: a bare-string recipient (the
   * removed legacy form) is a Warning; a literal object that can never decode to an AssetHolder is an Error; a
   * dynamic (`{"var":..}` / computed) recipient is left to the combiner.
   */
  private def checkTransferRecipients(t: Transition, idx: Int): List[Diagnostic] =
    transferRecipientExprs(t.effect).flatMap(recipientDiagnostic(_, idx))

  /** Pull each `_transferAsset` item's `recipient` sub-expression out of an effect (descends if-branch nesting). */
  private def transferRecipientExprs(expr: JsonLogicExpression): List[JsonLogicExpression] =
    expr match {
      case MapExpression(map) =>
        map.get(ReservedKeys.TRANSFER_ASSET).toList.flatMap(recipientsOfArray) ++
        map.values.toList.collect { case a: ApplyExpression => a }.flatMap(transferRecipientExprs)
      case ApplyExpression(_, args) => args.flatMap(transferRecipientExprs)
      case _                        => Nil
    }

  private def recipientsOfArray(arr: JsonLogicExpression): List[JsonLogicExpression] =
    arr match {
      case ArrayExpression(items) => items.collect { case MapExpression(m) => m.get(ReservedKeys.RECIPIENT) }.flatten
      case _                      => Nil
    }

  /**
   * Classify a recipient sub-expression's STATIC shape (None == well-formed, or dynamic / not statically
   * checkable). The valid variant/field keys come from [[AssetHolder.WireKeys]] (the single source), never
   * hardcoded here.
   */
  private def recipientDiagnostic(recipient: JsonLogicExpression, idx: Int): Option[Diagnostic] = {
    val loc = Location(Some(idx), "effect", Some("recipient"))
    recipient match {
      // a single-key {<Variant>:{<field>:..}} AssetHolder wrapper (Fiber/Wallet) with its required inner field
      case MapExpression(m) if m.size == 1 && AssetHolder.WireKeys.variants.contains(m.keys.head) =>
        val variant = m.keys.head
        val field = AssetHolder.WireKeys.fieldFor(variant) // Some for every key in `variants`
        if (field.exists(f => childHasKey(m(variant), f))) None
        else
          Some(
            Diagnostic(
              Severity.Error,
              "transfer-recipient-malformed",
              s"""_transferAsset recipient {"$variant":..} is missing its "${field.getOrElse("")}" field""",
              loc
            )
          )
      // a literal object that is not an AssetHolder variant wrapper — cannot decode to an AssetHolder
      case MapExpression(m) =>
        Some(
          Diagnostic(
            Severity.Error,
            "transfer-recipient-not-holder",
            s"_transferAsset recipient must be an AssetHolder object " +
            s"(${AssetHolder.WireKeys.variants.toList.sorted.map(v => s"""{"$v":..}""").mkString(" / ")}); " +
            s"got keys ${m.keys.toList.sorted.mkString("{", ",", "}")}",
            loc
          )
        )
      // a literal bare string — the removed legacy form; the chain now rejects it
      case ConstExpression(StrValue(_)) =>
        Some(
          Diagnostic(
            Severity.Warning,
            "transfer-recipient-bare-string",
            "_transferAsset recipient is a bare string; the chain requires the AssetHolder object form and rejects it",
            loc
          )
        )
      // dynamic ({"var":..} / computed) — shape can't be verified offline; the combiner checks the resolved value
      case _ => None
    }
  }

  private def childHasKey(expr: JsonLogicExpression, key: String): Boolean =
    expr match {
      case MapExpression(m)             => m.contains(key)
      case ConstExpression(MapValue(m)) => m.contains(key)
      case _                            => false
    }

  // ==========================================================================
  // (b3) directive-injection hazard — dynamically-computed TOP-LEVEL effect keys
  // ==========================================================================

  /**
   * Reserved `_`-directives are matched by key PREFIX on the evaluated effect RESULT (`EffectExtractor`), not
   * on the authored expression — so a transition whose effect computes a TOP-LEVEL key from data (a `set` with
   * a non-literal key, not nested under a state field) can let an attacker-controlled key beginning with `_`
   * inject a reserved directive (e.g. `_transferAsset`). The combiner's holder/`allowedEffects` gates bound the
   * damage, but the boundary itself is a string convention. This advisory flags the pattern so an author keeps
   * top-level effect keys literal (or sets a fail-closed `allowedEffects`). NOTE: nested dynamic keys (the
   * common `field: {set:[…event.x…]}` accumulator) are NOT flagged — they sit under a state field, never at
   * the top level the extractor scans.
   */
  private def checkInjectionHazard(t: Transition, idx: Int): List[Diagnostic] =
    if (topLevelDynamicKeyCount(t.effect) > 0)
      List(
        Diagnostic(
          Severity.Warning,
          "directive-injection-hazard",
          "effect computes a dynamic TOP-LEVEL key (a `set` with a non-literal key); a data-controlled key " +
          "beginning with `_` could inject a reserved directive into the effect result. Keep top-level effect " +
          "keys literal, or set a fail-closed `allowedEffects` policy.",
          Location(Some(idx), "effect", None)
        )
      )
    else Nil

  /**
   * Count `set` ops that write a NON-literal key at a TOP-LEVEL effect-result position. Descends only through
   * the result-shaping ops (`merge`/`if`) and a `set`'s target — never into a `MapExpression`'s values (those
   * are state-field data, not top-level keys), so a nested dynamic-key write does not false-positive.
   */
  private def topLevelDynamicKeyCount(expr: JsonLogicExpression): Int =
    expr match {
      case ApplyExpression(JsonLogicOp.MergeOp, args)  => args.map(topLevelDynamicKeyCount).sum
      case ApplyExpression(JsonLogicOp.IfElseOp, args) => args.map(topLevelDynamicKeyCount).sum
      case ApplyExpression(JsonLogicOp.SetOp, target :: keyExpr :: _) =>
        val here = keyExpr match { case ConstExpression(StrValue(_)) => 0; case _ => 1 }
        here + topLevelDynamicKeyCount(target)
      case _ => 0
    }

  // ==========================================================================
  // (c) reachability
  // ==========================================================================

  private def checkReachability(definition: StateMachineDefinition): List[Diagnostic] = {
    val adjacency: Map[StateId, List[StateId]] =
      definition.transitions.groupBy(_.from).view.mapValues(_.map(_.to)).toMap

    // BFS from initialState.
    val reached = scala.collection.mutable.Set(definition.initialState)
    val queue = scala.collection.mutable.Queue(definition.initialState)
    while (queue.nonEmpty) {
      val s = queue.dequeue()
      adjacency.getOrElse(s, Nil).foreach { n =>
        if (!reached.contains(n)) { reached += n; queue.enqueue(n) }
      }
    }

    val unreachable = definition.states.keySet.toList
      .filterNot(reached.contains)
      .sortBy(_.value)
      .map { sid =>
        Diagnostic(
          Severity.Error,
          "unreachable-state",
          s"""state "${sid.value}" is not reachable from initialState "${definition.initialState.value}"""",
          Location(None, "states", Some(sid.value))
        )
      }

    val statesWithOutgoing = definition.transitions.map(_.from).toSet
    val deadEnds = definition.states.toList
      .collect { case (sid, st) if !st.isFinal && !statesWithOutgoing.contains(sid) => sid }
      .sortBy(_.value)
      .map { sid =>
        Diagnostic(
          Severity.Warning,
          "dead-end-state",
          s"""state "${sid.value}" is not final and has no outgoing transition (a stuck state)""",
          Location(None, "states", Some(sid.value))
        )
      }

    unreachable ++ deadEnds
  }

  // ==========================================================================
  // (d) write conformance (only meaningful with a shape; internal consistency otherwise)
  // ==========================================================================

  private def checkWrites(t: Transition, idx: Int, shape: Option[MachineShape]): List[Diagnostic] =
    shape match {
      case None => Nil // internal-consistency mode writes are unconstrained
      case Some(s) =>
        val declared = s.stateMessage.fields.map(_.name).toSet
        effectOutputKeys(t.effect)
          .filterNot(ReservedKeys.isInternal)
          .toList
          .sorted
          .filterNot(declared.contains)
          .map { key =>
            Diagnostic(
              Severity.Error,
              "undeclared-state-write",
              s"""effect writes "$key", which is not a field of the declared state-shape """ +
              s"(${s.stateMessage.typeName})",
              Location(Some(idx), "effect", Some(key))
            )
          }
    }

  // ==========================================================================
  // (e) expression depth — reuse the existing FiberRules cap, surfaced as Errors
  // ==========================================================================

  private val tooDeepLoc = """transition\[(\d+)\]\.(guard|effect)""".r

  private def checkDepth(definition: StateMachineDefinition): List[Diagnostic] =
    FiberRules.L1
      .definitionExpressionsWithinDepthLimits[Id](definition)
      .fold(
        errs =>
          errs.toNonEmptyList.toList.map {
            case e @ CommonRules.Errors.ExpressionTooDeep(fieldName, _, _) =>
              val loc = fieldName match {
                case tooDeepLoc(i, f) => Location(Some(i.toInt), f, None)
                case _                => Location(None, fieldName, None)
              }
              Diagnostic(Severity.Error, "expression-too-deep", e.message, loc)
            case other =>
              Diagnostic(Severity.Error, "expression-too-deep", other.message, Location(None, "definition", None))
          },
        _ => Nil
      )

  // ==========================================================================
  // AST helpers
  // ==========================================================================

  /** Every literal `{"var":"path"}` (Left) string in an expression tree; recurses into computed-key vars. */
  private def collectVarPaths(expr: JsonLogicExpression): List[String] =
    expr match {
      case VarExpression(Left(path), _)    => List(path)
      case VarExpression(Right(nested), _) => collectVarPaths(nested)
      case MapExpression(map)              => map.values.toList.flatMap(collectVarPaths)
      case ApplyExpression(_, args)        => args.flatMap(collectVarPaths)
      case ArrayExpression(items)          => items.flatMap(collectVarPaths)
      case _: ConstExpression              => Nil
    }

  /** Every map key anywhere in an expression tree (both `MapExpression` and literal `ConstExpression(MapValue)`). */
  private def collectAllMapKeys(expr: JsonLogicExpression): List[String] =
    expr match {
      case MapExpression(map)              => map.keys.toList ++ map.values.toList.flatMap(collectAllMapKeys)
      case ApplyExpression(_, args)        => args.flatMap(collectAllMapKeys)
      case ArrayExpression(items)          => items.flatMap(collectAllMapKeys)
      case VarExpression(Right(nested), _) => collectAllMapKeys(nested)
      case VarExpression(Left(_), _)       => Nil
      case ConstExpression(value)          => collectValueMapKeys(value)
    }

  private def collectValueMapKeys(value: JsonLogicValue): List[String] =
    value match {
      case MapValue(map)     => map.keys.toList ++ map.values.toList.flatMap(collectValueMapKeys)
      case ArrayValue(items) => items.flatMap(collectValueMapKeys)
      case _                 => Nil
    }

  /**
   * Keys of the maps in RESULT position of an effect — the maps whose keys are merged into state (directive
   * keys + state-write keys). A plain `{...}` effect yields its own keys; an `{"if":[c, {A}, {B}]}` yields the
   * keys of both branches. Field VALUES are not descended into (a nested map value is state data, not a
   * top-level field).
   */
  private def effectOutputKeys(expr: JsonLogicExpression): Set[String] =
    expr match {
      case MapExpression(map)           => map.keySet
      case ConstExpression(MapValue(m)) => m.keySet
      case ApplyExpression(_, args)     => args.flatMap(effectOutputKeys).toSet
      case ArrayExpression(items)       => items.flatMap(effectOutputKeys).toSet
      case _                            => Set.empty
    }

  // ==========================================================================
  // String distance
  // ==========================================================================

  /** The candidate with the smallest edit distance to `s`, if within distance 2 (else None — no suggestion). */
  private def nearest(s: String, candidates: Set[String]): Option[String] =
    candidates.toList.map(c => c -> editDistance(s, c)).sortBy(_._2).headOption.collect { case (c, d) if d <= 2 => c }

  /** Standard Levenshtein edit distance. */
  private def editDistance(a: String, b: String): Int = {
    val prev = Array.tabulate(b.length + 1)(identity)
    val curr = new Array[Int](b.length + 1)
    var i = 1
    while (i <= a.length) {
      curr(0) = i
      var j = 1
      while (j <= b.length) {
        val cost = if (a(i - 1) == b(j - 1)) 0 else 1
        curr(j) = math.min(math.min(curr(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
        j += 1
      }
      System.arraycopy(curr, 0, prev, 0, b.length + 1)
      i += 1
    }
    prev(b.length)
  }
}
