package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, MapValue, StrValue}

import xyz.kd5ujc.schema.asset.AssetHolder
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.lifecycle.combine.CombineRejected

import io.circe.syntax._

/**
 * Extracts side effects from transition effect results.
 *
 * Effect results can contain special keys that signal side effects:
 * - _triggers: Cross-machine event triggers
 * - _scriptCall: Script invocation
 * - _outputs: External system outputs
 * - _spawn: Child machine creation
 *
 * Supports two result formats:
 * - MapValue: Direct key lookup (e.g., `{"_triggers": [...]}`)
 * - ArrayValue: Tuple format (e.g., `[["_triggers", [...]]]`)
 */
object EffectExtractor {

  /**
   * Extract a value from an effect result by reserved key.
   * Handles both MapValue (direct key) and ArrayValue (tuple format) representations.
   */
  private def extractByKey(effectResult: JsonLogicValue, key: String): Option[JsonLogicValue] =
    effectResult match {
      case MapValue(map) =>
        map.get(key)

      case ArrayValue(updates) =>
        updates.collectFirst {
          case ArrayValue(List(StrValue(k), value)) if k == key =>
            value
        }

      case _ => None
    }

  /**
   * Extract an array value from an effect result by reserved key.
   * Returns empty list if key not found or value is not an array.
   */
  private def extractArrayByKey(effectResult: JsonLogicValue, key: String): List[JsonLogicValue] =
    extractByKey(effectResult, key).collect { case ArrayValue(items) => items }.getOrElse(List.empty)

  /**
   * Extract ALL side effects from a transition's effect EXPRESSION as a single ordered list of typed
   * [[FiberEffect]]s. `_triggers` and `_scriptCall` become `Triggered`; `_spawn` directives become
   * `Spawned`; `_emit` events become `Emitted`; `_transferAsset` directives become `AssetTransferred`;
   * `_addDependency`/`_setDependencyActive` become `DependencyMutated`.
   *
   * SECURITY (directive-injection immunity) — a reserved directive is honoured ONLY when its KEY is
   * LITERALLY AUTHORED in the signed effect expression (`transition.effect`), never when the key is
   * COMPUTED from event data. The injection vector this closes: an effect whose top-level key is built
   * from data — e.g. `{"merge":[{"var":"state"},{"set":[{}, {"var":"event.k"}, {"var":"event.v"}]}]}`
   * with attacker-sent `event.k = "_transferAsset"` — would, under result-based extraction (reading
   * `key.startsWith("_")` off the post-evaluation result), forge a `_transferAsset` directive and drain
   * the fiber's held assets. Here directives are sourced from [[authoredDirectiveResult]], which walks
   * the AUTHORED AST: a data-computed key is never a literal key in the AST, so it can never become a
   * directive, and the directive VALUE is evaluated from the authored sub-expression (never substituted
   * by a `merge`/`set` of attacker data). `_spawn` was already expression-extracted and is unchanged.
   *
   * Conditional/merge directives are preserved: [[authoredDirectiveResult]] evaluates `if`-conditions to
   * pick the taken branch and unions `merge` operands, so a directive authored inside an `if`/`merge`
   * fires exactly when the surrounding branch would have surfaced it.
   *
   * Dispatch is a TYPED, EXHAUSTIVE registry over [[FiberDirective]] ([[handlerFor]]): every directive has a
   * handler, enforced at compile time, and the emitted order is `FiberDirective.values` (triggers, script
   * call, spawn, emit, transfer, dependency — unchanged from the prior hand-wired sequence). Gas for
   * payload/args/directive evaluation is charged in that order via [[MeteredEvaluator]].
   */
  def extractEffects[F[_]: Async, G[_]: Monad](
    effectExpr:    JsonLogicExpression,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[FiberEffect]] =
    authoredDirectiveResult[F, G](effectExpr, contextData).flatMap { authored =>
      // `authored` = a synthetic MapValue holding ONLY the directive keys LITERALLY authored in the effect
      // expression, evaluated from their authored sub-expressions. Every result-position handler reads
      // directives from THIS, never from the raw (injectable) effect result; the `Spawn` handler reads the
      // effect EXPRESSION directly (already immune).
      val ctx = EffectContext(effectExpr, authored, contextData, sourceFiberId)
      FiberDirective.values.toList.flatTraverse(directive => handlerFor[F, G](directive).apply(ctx))
    }

  /** Everything a directive extractor needs; `authored` is the injection-immune authored directive result. */
  final case class EffectContext(
    effectExpr:    JsonLogicExpression,
    authored:      JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )

  /**
   * The typed directive registry: a TOTAL match over [[FiberDirective]] returning the REAL extractor for that
   * directive as `EffectContext => G[List[FiberEffect]]`. Because the match is exhaustive over a sealed enum,
   * introducing a new `FiberDirective` without wiring its handler is a compile error — the exhaustiveness
   * guard, with real wiring (no stringly-typed indirection).
   */
  private def handlerFor[F[_]: Async, G[_]: Monad](
    directive: FiberDirective
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): EffectContext => G[List[FiberEffect]] =
    directive match {
      case FiberDirective.Triggers =>
        ctx =>
          extractTriggerEvents[F, G](ctx.authored, ctx.contextData, ctx.sourceFiberId).map(
            _.map(t => FiberEffect.Triggered(t): FiberEffect)
          )

      case FiberDirective.ScriptCall =>
        ctx =>
          extractScriptCall[F, G](ctx.authored, ctx.contextData, ctx.sourceFiberId)
            .map(_.toList.map(t => FiberEffect.Triggered(t): FiberEffect))

      // Spawn is sourced from the effect EXPRESSION (already injection-immune), not the authored result.
      case FiberDirective.Spawn =>
        ctx =>
          extractSpawnDirectivesFromExpression(ctx.effectExpr).map(d => FiberEffect.Spawned(d): FiberEffect).pure[G]

        // `extractEmittedEvents` stamps the EMITTING fiber id (`sourceFiberId`) into every emitted event — the
      // emitter, distinct from any cross-fiber caller. Loud on a malformed `_emit` item (audit L5).
      case FiberDirective.Emit =>
        ctx =>
          extractEmittedEvents[F, G](ctx.authored, ctx.sourceFiberId).map(
            _.map(e => FiberEffect.Emitted(e): FiberEffect)
          )

      case FiberDirective.Transfer =>
        ctx => extractAssetTransfers[F, G](ctx.authored, ctx.contextData).map(_.map(x => x: FiberEffect))

      case FiberDirective.Dependency =>
        ctx => extractDependencyMutations[F, G](ctx.authored, ctx.contextData).map(_.map(x => x: FiberEffect))
    }

  /**
   * Reserved directive keys honoured from the effect RESULT position (everything except `_spawn`, which has
   * its own expression extractor [[extractSpawnDirectivesFromExpression]] and is already immune). Single
   * source of truth: derived from [[FiberDirective]].
   */
  private val resultDirectiveKeys: Set[String] = FiberDirective.resultKeys

  /**
   * Build the injection-immune "authored result": a [[MapValue]] containing ONLY the reserved directive
   * keys that are LITERALLY authored in `effectExpr` (in a result-surfacing position), each mapped to the
   * value obtained by evaluating its AUTHORED sub-expression against `contextData`. A directive key that
   * only appears in the post-evaluation result because event data flowed into a computed key
   * (`set`/`merge` with a `{"var":…}` key) is NOT a literal AST key, so it is absent here and never honoured.
   *
   * Fast path: if the expression authors no directive key at all (the overwhelmingly common case — pure
   * state effects), this returns the empty map WITHOUT evaluating anything, so directive-free transitions
   * stay byte-for-byte gas-neutral.
   */
  def authoredDirectiveResult[F[_]: Async, G[_]: Monad](
    effectExpr:  JsonLogicExpression,
    contextData: JsonLogicValue
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[JsonLogicValue] =
    if (!hasAuthoredDirective(effectExpr)) (MapValue.empty: JsonLogicValue).pure[G]
    else
      collectDirectiveEntries[F, G](effectExpr, contextData).map { entries =>
        val combined = entries
          .groupBy(_._1)
          .map { case (k, kvs) => k -> combineDirectiveValues(kvs.map(_._2)) }
        MapValue(combined): JsonLogicValue
      }

  /**
   * Pure static scan mirroring the result-surfacing positions of [[collectDirectiveEntries]]: does
   * `expr` literally author any reserved directive key that could surface as a top-level result key?
   * Used purely to keep directive-free effects from paying for the evaluating walker.
   */
  private def hasAuthoredDirective(expr: JsonLogicExpression): Boolean =
    expr match {
      case MapExpression(m)                            => m.keys.exists(resultDirectiveKeys)
      case ConstExpression(MapValue(m))                => m.keys.exists(resultDirectiveKeys)
      case ApplyExpression(JsonLogicOp.MergeOp, args)  => args.exists(hasAuthoredDirective)
      case ApplyExpression(JsonLogicOp.IfElseOp, args) => args.exists(hasAuthoredDirective)
      case ApplyExpression(JsonLogicOp.SetOp, obj :: keyExpr :: _ :: Nil) =>
        hasAuthoredDirective(obj) || (keyExpr match {
          case ConstExpression(StrValue(k)) => resultDirectiveKeys(k)
          case _                            => false
        })
      case ArrayExpression(elems)             => elems.exists(tupleDirectiveKey(_).isDefined)
      case ConstExpression(ArrayValue(elems)) => elems.exists(constTupleDirectiveKey(_).isDefined)
      case _                                  => false
    }

  /**
   * Walk the AUTHORED effect expression and collect `(directiveKey, evaluatedValue)` pairs from every
   * result-surfacing position, evaluating each authored directive value sub-expression against `contextData`:
   *   - [[MapExpression]] / literal map: a LITERAL directive key → evaluate its value sub-expression.
   *   - `merge`: union of operands → recurse into every operand.
   *   - `if`: result is the taken branch → evaluate the conditions, recurse into the selected branch ONLY
   *     (so a directive in a not-taken branch never fires — matching the old result-based semantics).
   *   - `set`: a LITERAL string directive key arg is honoured (recurse the base object too); a COMPUTED
   *     key arg (the injection vector) contributes nothing.
   *   - `[[key, value], …]` tuple-update forms (the `ArrayValue` effect shape): literal directive keys only.
   * Anything else (a bare `var`, an arbitrary op, …) surfaces no authored directive.
   */
  private def collectDirectiveEntries[F[_]: Async, G[_]: Monad](
    expr:        JsonLogicExpression,
    contextData: JsonLogicValue
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[(String, JsonLogicValue)]] =
    expr match {
      case MapExpression(m) =>
        m.toList.flatTraverse {
          case (k, vExpr) if resultDirectiveKeys(k) => evalDirectiveValue[F, G](k, vExpr, contextData)
          case _                                    => List.empty[(String, JsonLogicValue)].pure[G]
        }

      case ConstExpression(MapValue(m)) =>
        m.toList.collect { case (k, v) if resultDirectiveKeys(k) => k -> v }.pure[G]

      case ApplyExpression(JsonLogicOp.MergeOp, args) =>
        args.flatTraverse(collectDirectiveEntries[F, G](_, contextData))

      case ApplyExpression(JsonLogicOp.IfElseOp, args) =>
        selectIfBranch[F, G](args, contextData).flatMap {
          case Some(branch) => collectDirectiveEntries[F, G](branch, contextData)
          case None         => List.empty[(String, JsonLogicValue)].pure[G]
        }

      case ApplyExpression(JsonLogicOp.SetOp, obj :: keyExpr :: valExpr :: Nil) =>
        for {
          base <- collectDirectiveEntries[F, G](obj, contextData)
          extra <- keyExpr match {
            case ConstExpression(StrValue(k)) if resultDirectiveKeys(k) =>
              evalDirectiveValue[F, G](k, valExpr, contextData)
            case _ => List.empty[(String, JsonLogicValue)].pure[G]
          }
        } yield base ++ extra

      case ArrayExpression(elems) =>
        elems.flatTraverse { elem =>
          tupleDirectiveKey(elem) match {
            case Some((k, vExpr)) => evalDirectiveValue[F, G](k, vExpr, contextData)
            case None             => List.empty[(String, JsonLogicValue)].pure[G]
          }
        }

      case ConstExpression(ArrayValue(elems)) =>
        elems.flatMap(constTupleDirectiveKey).pure[G]

      case _ => List.empty[(String, JsonLogicValue)].pure[G]
    }

  /** Evaluate one authored directive value sub-expression; a failed/dropped eval yields no entry. */
  private def evalDirectiveValue[F[_]: Async, G[_]: Monad](
    key:         String,
    valueExpr:   JsonLogicExpression,
    contextData: JsonLogicValue
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[(String, JsonLogicValue)]] =
    MeteredEvaluator
      .evalOpt[F, G](valueExpr, contextData, GasExhaustionPhase.Effect)
      .map(_.map(key -> _).toList)

  /**
   * Replicate metakit's `if` branch selection over the AUTHORED condition expressions: walk `(cond, branch)`
   * pairs, evaluate each condition against `contextData`, return the first truthy branch, else the trailing
   * `else`. A condition that fails to evaluate is treated as not-taken (graceful), and a malformed (even)
   * arg list yields no branch — such an effect would already have aborted in the main effect evaluation.
   */
  private def selectIfBranch[F[_]: Async, G[_]: Monad](
    args:        List[JsonLogicExpression],
    contextData: JsonLogicValue
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[Option[JsonLogicExpression]] =
    args match {
      case cond :: branch :: rest =>
        MeteredEvaluator.evalOpt[F, G](cond, contextData, GasExhaustionPhase.Effect).flatMap {
          case Some(v) if v.isTruthy => (Some(branch): Option[JsonLogicExpression]).pure[G]
          case _                     => selectIfBranch[F, G](rest, contextData)
        }
      case lastElse :: Nil => (Some(lastElse): Option[JsonLogicExpression]).pure[G]
      case Nil             => (None: Option[JsonLogicExpression]).pure[G]
    }

  /** A literal `[directiveKey, valueExpr]` tuple in the array-update effect form, or `None`. */
  private def tupleDirectiveKey(elem: JsonLogicExpression): Option[(String, JsonLogicExpression)] =
    elem match {
      case ArrayExpression(ConstExpression(StrValue(k)) :: valExpr :: Nil) if resultDirectiveKeys(k) =>
        Some(k -> valExpr)
      case _ => None
    }

  /** A literal `[directiveKey, value]` tuple in a fully-constant array-update effect, or `None`. */
  private def constTupleDirectiveKey(elem: JsonLogicValue): Option[(String, JsonLogicValue)] =
    elem match {
      case ArrayValue(StrValue(k) :: v :: Nil) if resultDirectiveKeys(k) => Some(k -> v)
      case _                                                             => None
    }

  /**
   * Combine the values collected for a single directive key across multiple surfacing positions (e.g. a
   * `merge` of two maps that each author `_triggers`). Array directives concatenate (preserving authored
   * order); a non-array directive (`_scriptCall`) takes the last, matching `merge`'s last-wins for maps.
   */
  private def combineDirectiveValues(values: List[JsonLogicValue]): JsonLogicValue =
    values match {
      case single :: Nil => single
      case many if many.forall(_.isInstanceOf[ArrayValue]) =>
        ArrayValue(many.flatMap { case ArrayValue(items) => items; case _ => List.empty })
      case many => many.lastOption.getOrElse(NullValue)
    }

  /**
   * Extract trigger events with gas metering via StateT.
   *
   * Gas is charged to the execution state automatically via Stateful.
   * Gas limit and config are read from FiberContext via Ask.
   *
   * @param effectResult  The result from effect evaluation containing trigger definitions
   * @param contextData   Context data for evaluating payload expressions
   * @param sourceFiberId The fiber that is emitting these triggers
   * @return List of triggers (gas charged via state)
   */
  def extractTriggerEvents[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[List[FiberTrigger]] =
    extractArrayByKey(effectResult, ReservedKeys.TRIGGERS)
      .traverse(parseTriggerEvent[F, G](_, contextData, sourceFiberId))

  private def parseTriggerEvent[F[_]: Async, G[_]: Monad](
    value:         JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[FiberTrigger] =
    value match {
      case MapValue(triggerMap) =>
        for {
          targetIdStr <- requireString[F, G](triggerMap, ReservedKeys.TARGET_MACHINE_ID, "_triggers", "targetMachineId")
          targetId    <- requireUuid[F, G](targetIdStr, "_triggers", "targetMachineId")
          eventType   <- requireString[F, G](triggerMap, ReservedKeys.EVENT_NAME, "_triggers", "eventName")
          payloadValue <- requireField[F, G](triggerMap, ReservedKeys.PAYLOAD, "_triggers", "payload")
          evaluatedPayload <- evalOrReject[F, G](
            payloadValue,
            contextData,
            GasExhaustionPhase.Trigger,
            "_triggers",
            "payload"
          )
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(eventType, evaluatedPayload),
          sourceFiberId = Some(sourceFiberId)
        )
      case other =>
        rejectDirective[F, G, FiberTrigger]("_triggers", s"malformed item (expected an object), got $other")
    }

  /**
   * Extract script call with gas metering via StateT.
   *
   * Gas is charged to the execution state automatically via Stateful.
   * Gas limit and config are read from FiberContext via Ask.
   *
   * @param effectResult  The result from effect evaluation containing script call definition
   * @param contextData   Context data for evaluating args expressions
   * @param sourceFiberId The fiber that is making this script call
   * @return Optional script trigger (gas charged via state)
   */
  def extractScriptCall[F[_]: Async, G[_]: Monad](
    effectResult:  JsonLogicValue,
    contextData:   JsonLogicValue,
    sourceFiberId: UUID
  )(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G): G[Option[FiberTrigger]] =
    extractByKey(effectResult, ReservedKeys.SCRIPT_CALL) match {
      case None => none[FiberTrigger].pure[G] // absent directive ⇒ no-op (not malformed)
      case Some(MapValue(scriptCallMap)) =>
        for {
          cidStr        <- requireString[F, G](scriptCallMap, ReservedKeys.FIBER_ID, "_scriptCall", "fiberId")
          targetId      <- requireUuid[F, G](cidStr, "_scriptCall", "fiberId")
          method        <- requireString[F, G](scriptCallMap, ReservedKeys.METHOD, "_scriptCall", "method")
          argsValue     <- requireField[F, G](scriptCallMap, ReservedKeys.ARGS, "_scriptCall", "args")
          evaluatedArgs <- evalOrReject[F, G](argsValue, contextData, GasExhaustionPhase.Trigger, "_scriptCall", "args")
        } yield FiberTrigger(
          targetFiberId = targetId,
          input = FiberInput.Transition(method, evaluatedArgs),
          sourceFiberId = Some(sourceFiberId)
        ).some

      case Some(other) =>
        rejectDirective[F, G, Option[FiberTrigger]]("_scriptCall", s"must be an object, got $other")
    }

  /**
   * Extract fiber-held asset custody transfers (`_transferAsset`, asset-model.md §10) with gas metering via
   * StateT. Mirrors [[extractTriggerEvents]] EXACTLY: the directive's `assetId`/`recipient` JSON-Logic
   * expressions are RESOLVED here (against the transition context), so the resulting [[FiberEffect.AssetTransferred]]
   * carries values, not logic. Gas is charged under [[GasExhaustionPhase.Morphism]]. The `recipient` is the
   * canonical [[AssetHolder]] OBJECT form ONLY (`{"Fiber":{"fiberId":..}}` / `{"Wallet":{"address":..}}`) —
   * see [[parseAssetTransfer]]. A malformed directive (non-UUID `assetId`, a `recipient` that is not a
   * well-formed `AssetHolder` object, or a gas/eval failure) raises a graceful [[CombineRejected]] — NOT a
   * silent drop; surfacing the parse failure is deliberate (a silently-dropped transfer is a latent bug).
   *
   * SECURITY: this extractor carries NO authorization. The combiner ([[xyz.kd5ujc.shared_data.lifecycle.combine.AssetCombiner.applyFiberTransfer]])
   * enforces `holder == AssetHolder.Fiber(emittingFiberId)` before applying any extracted transfer (R1).
   */
  def extractAssetTransfers[F[_]: Async, G[_]: Monad](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[List[FiberEffect.AssetTransferred]] =
    extractArrayByKey(effectResult, ReservedKeys.TRANSFER_ASSET)
      .traverse(item => parseAssetTransfer[F, G](item, contextData))

  /**
   * Parse one `_transferAsset` directive item into an [[FiberEffect.AssetTransferred]]. The `assetId` /
   * `recipient` expressions are RESOLVED here (gas charged under [[GasExhaustionPhase.Morphism]]) so the
   * effect carries values, not logic.
   *
   * The recipient is the canonical [[AssetHolder]] OBJECT form ONLY — `{"Fiber":{"fiberId":..}}` /
   * `{"Wallet":{"address":..}}` — decoded strictly through the same magnolia `AssetHolder` codec used on every
   * other surface (`MintAsset.holder`, `ApplyMorphism.recipient`, `AssetRecord.holder`). The old bare-string
   * UUID/DAG-address disambiguation is GONE. Any malformation — a non-object item, a missing/non-UUID
   * `assetId`, a recipient that is not a well-formed `AssetHolder` object, or a gas/eval failure — raises a
   * graceful [[CombineRejected]] (NOT a silent drop): extraction runs only on the combiner apply path, so the
   * raise is caught at `Combiner.insert` → `RejectionReceipt` (rule #2; the same authoritative-gate pattern
   * `AssetCombiner` already uses), never a block-acceptance `Invalid`. Surfacing the error is deliberate — a
   * silently-dropped transfer is a latent bug, not a no-op.
   */
  private def parseAssetTransfer[F[_]: Async, G[_]: Monad](
    value:       JsonLogicValue,
    contextData: JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[FiberEffect.AssetTransferred] =
    value match {
      case MapValue(transferMap) =>
        for {
          assetIdValue <- requireField[F, G](transferMap, ReservedKeys.ASSET_ID, "_transferAsset", "assetId")
          evaluatedAssetId <- evalOrReject[F, G](
            assetIdValue,
            contextData,
            GasExhaustionPhase.Morphism,
            "_transferAsset",
            "assetId"
          )
          assetId <- evaluatedAssetId match {
            case StrValue(s) =>
              scala.util.Try(UUID.fromString(s)).toOption match {
                case Some(uuid) => uuid.pure[G]
                case None       => rejectDirective[F, G, UUID]("_transferAsset", s"assetId is not a valid UUID: '$s'")
              }
            case other => rejectDirective[F, G, UUID]("_transferAsset", s"assetId must be a UUID string, got $other")
          }
          recipientValue <- requireField[F, G](transferMap, ReservedKeys.RECIPIENT, "_transferAsset", "recipient")
          evaluatedRecipient <- evalOrReject[F, G](
            recipientValue,
            contextData,
            GasExhaustionPhase.Morphism,
            "_transferAsset",
            "recipient"
          )
          recipient <- evaluatedRecipient match {
            case MapValue(_) =>
              evaluatedRecipient.asJson.as[AssetHolder] match {
                case Right(holder) => holder.pure[G]
                case Left(err) =>
                  rejectDirective[F, G, AssetHolder](
                    "_transferAsset",
                    s"recipient is not a valid AssetHolder object {Fiber|Wallet}: ${err.getMessage}"
                  )
              }
            case other =>
              rejectDirective[F, G, AssetHolder](
                "_transferAsset",
                s"""recipient must be an AssetHolder object {"Fiber":{"fiberId":..}} / {"Wallet":{"address":..}}, got $other"""
              )
          }
        } yield FiberEffect.AssetTransferred(assetId = assetId, recipient = recipient)
      case other =>
        rejectDirective[F, G, FiberEffect.AssetTransferred](
          "_transferAsset",
          s"malformed item (expected an object), got $other"
        )
    }

  // ── Loud directive parsing (audit 2026-07-07, finding L5) ─────────────────────────────────────────────
  // A MALFORMED reserved directive (wrong type / missing required field / non-UUID / gas-or-eval failure) is
  // NOT silently dropped — it raises a graceful [[CombineRejected]] naming the directive + what is wrong, the
  // SAME loud mode `_transferAsset` already uses. Extraction runs ONLY on the combiner apply path
  // (FiberEvaluator ← FiberEngine.process/migrate & TriggerHandler cascade ← Combiner.insert), so the raise
  // surfaces as a `RejectionReceipt` (rule #2), NEVER a block-acceptance `Invalid`. An ABSENT directive is a
  // no-op (empty array ⇒ no items ⇒ no raise); only a PRESENT-but-malformed item is loud — a silently-dropped
  // trigger/scriptCall/dependency/emit is a latent value-safety bug, not a benign no-op.

  /** Raise a graceful, combiner-caught [[CombineRejected]] for a malformed `directive`. */
  private def rejectDirective[F[_]: Async, G[_], A](directive: String, reason: String)(implicit lift: F ~> G): G[A] =
    lift(Async[F].raiseError[A](CombineRejected(s"$directive: $reason")))

  /** Look up a required `directive` field, or raise a graceful [[CombineRejected]] naming what is missing. */
  private def requireField[F[_]: Async, G[_]: Monad](
    map:       Map[String, JsonLogicValue],
    key:       String,
    directive: String,
    label:     String
  )(implicit lift: F ~> G): G[JsonLogicValue] =
    map.get(key) match {
      case Some(v) => v.pure[G]
      case None    => rejectDirective[F, G, JsonLogicValue](directive, s"missing $label")
    }

  /** A required string `directive` field, or a graceful [[CombineRejected]] (missing / not a string). */
  private def requireString[F[_]: Async, G[_]: Monad](
    map:       Map[String, JsonLogicValue],
    key:       String,
    directive: String,
    label:     String
  )(implicit lift: F ~> G): G[String] =
    requireField[F, G](map, key, directive, label).flatMap {
      case StrValue(s) => s.pure[G]
      case other       => rejectDirective[F, G, String](directive, s"$label must be a string, got $other")
    }

  /** Parse a required UUID, or raise a graceful [[CombineRejected]] naming the offending value. */
  private def requireUuid[F[_]: Async, G[_]: Monad](
    raw:       String,
    directive: String,
    label:     String
  )(implicit lift: F ~> G): G[UUID] =
    scala.util.Try(UUID.fromString(raw)).toOption match {
      case Some(u) => u.pure[G]
      case None    => rejectDirective[F, G, UUID](directive, s"$label is not a valid UUID: '$raw'")
    }

  /** Evaluate a directive sub-expression under `phase`; a `Left` (gas/eval failure) is LOUD (rejects). */
  private def evalOrReject[F[_]: Async, G[_]: Monad](
    value:       JsonLogicValue,
    contextData: JsonLogicValue,
    phase:       GasExhaustionPhase,
    directive:   String,
    label:       String
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[JsonLogicValue] =
    MeteredEvaluator
      .eval[F, G](ExpressionParser.valueToExpression(value), contextData, phase)
      .flatMap {
        case Right(v)     => v.pure[G]
        case Left(reason) => rejectDirective[F, G, JsonLogicValue](directive, s"$label did not evaluate: $reason")
      }

  /**
   * Extract dynamic-dependency mutations (`_addDependency` / `_setDependencyActive`) with gas metering.
   * Mirrors [[extractAssetTransfers]]: each directive's `fiberId` JSON-Logic expression is RESOLVED here
   * against the transition context (gas charged under [[GasExhaustionPhase.DependencyMutation]]), so the
   * resulting [[FiberEffect.DependencyMutated]] carries a value, not logic. `_addDependency` forces
   * `active = true`; `_setDependencyActive` reads the directive's `active` boolean. A malformed directive
   * (missing/non-UUID `fiberId`, or a `_setDependencyActive` lacking a boolean `active`) is DROPPED — the
   * same fail-silent mode the other extractors use. The append-only ledger + DoS bounds are applied later,
   * by the engine ([[xyz.kd5ujc.shared_data.fiber.core.DependencyLedger]]).
   */
  def extractDependencyMutations[F[_]: Async, G[_]: Monad](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[List[FiberEffect.DependencyMutated]] =
    for {
      adds <- extractArrayByKey(effectResult, ReservedKeys.ADD_DEPENDENCY)
        .traverse(parseDependencyMutation[F, G](_, contextData, "_addDependency", forcedActive = Some(true)))
      sets <- extractArrayByKey(effectResult, ReservedKeys.SET_DEPENDENCY_ACTIVE)
        .traverse(parseDependencyMutation[F, G](_, contextData, "_setDependencyActive", forcedActive = None))
    } yield adds ++ sets

  private def parseDependencyMutation[F[_]: Async, G[_]: Monad](
    value:        JsonLogicValue,
    contextData:  JsonLogicValue,
    directive:    String,
    forcedActive: Option[Boolean]
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[FiberEffect.DependencyMutated] =
    value match {
      case MapValue(depMap) =>
        for {
          fiberIdValue <- requireField[F, G](depMap, ReservedKeys.FIBER_ID, directive, "fiberId")
          // Charge gas for fiberId resolution under the DependencyMutation phase.
          evaluatedFiberId <- evalOrReject[F, G](
            fiberIdValue,
            contextData,
            GasExhaustionPhase.DependencyMutation,
            directive,
            "fiberId"
          )
          fiberIdStr <- evaluatedFiberId match {
            case StrValue(s) => s.pure[G]
            case other       => rejectDirective[F, G, String](directive, s"fiberId must be a string, got $other")
          }
          fiberId <- requireUuid[F, G](fiberIdStr, directive, "fiberId")
          active <- forcedActive match {
            case Some(b) => b.pure[G]
            case None =>
              depMap.get(ReservedKeys.ACTIVE) match {
                case Some(BoolValue(b)) => b.pure[G]
                case Some(other) => rejectDirective[F, G, Boolean](directive, s"active must be a boolean, got $other")
                case None        => rejectDirective[F, G, Boolean](directive, "missing active")
              }
          }
        } yield FiberEffect.DependencyMutated(fiberId, active)
      case other =>
        rejectDirective[F, G, FiberEffect.DependencyMutated](
          directive,
          s"malformed item (expected an object), got $other"
        )
    }

  /**
   * `_emit` emitter-stamping. `.zipWithIndex` runs over the RAW `_emit` array, so `emissionIndex` is the true
   * authoring-time position. A malformed sibling is now LOUD (audit L5): it raises a graceful [[CombineRejected]]
   * rather than a silent drop, so the survivors are never sparse (the first malformed item aborts the whole
   * transition). `emitterFiberId` is the engine-supplied id of the fiber whose transition ran `_emit` — never
   * user-supplied, so the stamp cannot be forged from inside a guard/effect expression.
   */
  def extractEmittedEvents[F[_]: Async, G[_]: Monad](
    effectResult:   JsonLogicValue,
    emitterFiberId: UUID
  )(implicit lift: F ~> G): G[List[EmittedEvent]] =
    extractArrayByKey(effectResult, ReservedKeys.EMIT).zipWithIndex.traverse { case (v, i) =>
      parseEmittedEvent[F, G](v, emitterFiberId, i)
    }

  private def parseEmittedEvent[F[_]: Async, G[_]: Monad](
    value:          JsonLogicValue,
    emitterFiberId: UUID,
    emissionIndex:  Int
  )(implicit lift: F ~> G): G[EmittedEvent] =
    value match {
      case MapValue(m) =>
        for {
          name <- m.get(ReservedKeys.NAME) match {
            case Some(StrValue(n)) => n.pure[G]
            case Some(other)       => rejectDirective[F, G, String]("_emit", s"name must be a string, got $other")
            case None              => rejectDirective[F, G, String]("_emit", "missing name")
          }
          data <- m.get(ReservedKeys.DATA) match {
            case Some(d) => d.pure[G]
            case None    => rejectDirective[F, G, JsonLogicValue]("_emit", "missing data")
          }
          destination = m.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
        } yield EmittedEvent(name, data, destination, emitterFiberId, emissionIndex)
      case other =>
        rejectDirective[F, G, EmittedEvent]("_emit", s"malformed item (expected an object), got $other")
    }

  def extractSpawnDirectivesFromExpression(effectExpr: JsonLogicExpression): List[SpawnDirective] =
    effectExpr match {
      case MapExpression(map) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayExpression(spawns) =>
            spawns.flatMap(parseSpawnFromExpression)
          }
          .getOrElse(List.empty)

      case ConstExpression(MapValue(map)) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayValue(spawns) =>
            spawns.flatMap(parseSpawnFromValue)
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  private def parseSpawnFromExpression(expr: JsonLogicExpression): Option[SpawnDirective] =
    expr match {
      case MapExpression(spawnMap) =>
        for {
          childIdExpr     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defExpr         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition      <- ExpressionParser.parseStateMachineDefinitionFromExpression(defExpr)
          initialDataExpr <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield SpawnDirective(
          childIdExpr = childIdExpr,
          definition = definition,
          initialData = initialDataExpr,
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS)
        )
      case _ => None
    }

  private def parseSpawnFromValue(value: JsonLogicValue): Option[SpawnDirective] =
    value match {
      case MapValue(spawnMap) =>
        for {
          childIdValue     <- spawnMap.get(ReservedKeys.CHILD_ID)
          defValue         <- spawnMap.get(ReservedKeys.DEFINITION)
          definition       <- ExpressionParser.parseStateMachineDefinition(defValue)
          initialDataValue <- spawnMap.get(ReservedKeys.INITIAL_DATA)
        } yield SpawnDirective(
          childIdExpr = ConstExpression(childIdValue),
          definition = definition,
          initialData = ConstExpression(initialDataValue),
          ownersExpr = spawnMap.get(ReservedKeys.OWNERS).map(ConstExpression(_))
        )
      case _ => None
    }
}
