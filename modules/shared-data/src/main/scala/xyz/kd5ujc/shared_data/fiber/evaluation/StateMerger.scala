package xyz.kd5ujc.shared_data.fiber.evaluation

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.StrValue

import xyz.kd5ujc.schema.fiber.{FailureReason, GasExhaustionPhase, ReservedKeys}

/**
 * Centralized state merging with unified semantics.
 *
 * Supports two merge formats:
 * - MapValue: Shallow merge of keys (new values overwrite old)
 * - ArrayValue of [key, value] pairs: Sequential updates
 *
 * Internal/reserved keys are automatically filtered out.
 */
trait StateMerger[F[_]] {

  /**
   * Merge effect result into current state.
   *
   * @param currentState The current fiber state (must be MapValue)
   * @param effectResult The effect evaluation result
   * @return Either a failure reason or the merged state
   */
  def mergeEffectIntoState(
    currentState: MapValue,
    effectResult: JsonLogicValue
  ): F[Either[FailureReason, MapValue]]
}

object StateMerger {

  /**
   * Create a StateMerger instance.
   */
  def make[F[_]: Monad]: StateMerger[F] = new StateMerger[F] {

    def mergeEffectIntoState(
      currentState: MapValue,
      effectResult: JsonLogicValue
    ): F[Either[FailureReason, MapValue]] =
      effectResult match {
        case m: MapValue =>
          mergeMapValue(currentState, m)

        case ArrayValue(updates) =>
          mergeArrayUpdates(currentState, updates)

        case _ =>
          // COMMITTED (audit L1): render the value kind through OttoChain's own stable `ValueKind`, never
          // metakit's `getClass.getSimpleName` (a class rename would fork a mixed-version set on a rejected tx).
          (FailureReason.EvaluationError(
            GasExhaustionPhase.Effect,
            s"Effect must return MapValue or ArrayValue, got: ${ValueKind.of(effectResult)}"
          ): FailureReason)
            .asLeft[MapValue]
            .pure[F]
      }

    /**
     * Merge a MapValue effect into current state.
     * Filters out internal/reserved keys.
     */
    private def mergeMapValue(
      currentState: MapValue,
      effectMap:    MapValue
    ): F[Either[FailureReason, MapValue]] = {
      val filteredMap = effectMap.value.filterNot { case (key, _) => ReservedKeys.isInternal(key) }
      MapValue(currentState.value ++ filteredMap).asRight[FailureReason].pure[F]
    }

    /**
     * Merge an array of [key, value] pairs into current state.
     * Each element must be ArrayValue(List(StrValue(key), value)).
     */
    private def mergeArrayUpdates(
      currentState: MapValue,
      updates:      List[JsonLogicValue]
    ): F[Either[FailureReason, MapValue]] =
      updates
        .foldLeftM[F, Either[FailureReason, Map[String, JsonLogicValue]]](
          currentState.value.asRight
        ) {
          case (Right(acc), ArrayValue(List(StrValue(key), value))) if !ReservedKeys.isInternal(key) =>
            (acc + (key -> value)).asRight[FailureReason].pure[F]

          case (Right(acc), ArrayValue(List(StrValue(key), _))) if ReservedKeys.isInternal(key) =>
            acc.asRight[FailureReason].pure[F]

          case (Left(err), _) =>
            err.asLeft[Map[String, JsonLogicValue]].pure[F]

          case (_, other) =>
            // COMMITTED (audit L1): describe the offending element by OttoChain's stable `ValueKind`, never by
            // interpolating the metakit value's `toString` (a case-class rename/field-change would fork a
            // mixed-version set on a rejected tx).
            (FailureReason.EvaluationError(
              GasExhaustionPhase.Effect,
              s"Invalid effect update format, expected [key, value] array (got: ${ValueKind.of(other)})"
            ): FailureReason)
              .asLeft[Map[String, JsonLogicValue]]
              .pure[F]
        }
        .map(_.map(MapValue(_)))
  }

  /**
   * Default singleton instance for use without effect context.
   * Suitable for pure computations.
   */
  def default[F[_]: Monad]: StateMerger[F] = make[F]
}
