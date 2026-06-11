package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.UUID

import cats.Applicative
import cats.data.Validated
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.numerics.RatioOps.implicits._

import xyz.kd5ujc.schema.OnChain
import xyz.kd5ujc.shared_data.lifecycle.validate.{Limits, ValidationResult}

/**
 * Common validation rules shared between fiber and oracle domains.
 *
 * These rules operate on shared concepts like CID uniqueness and
 * structural validation of JsonLogic values/expressions.
 */
object CommonRules {

  // ============== CID Rules ==============

  /** Validates that a CID is not already in use (for create operations) */
  def cidNotUsed[F[_]: Applicative](cid: UUID, state: OnChain): F[ValidationResult] =
    Validated
      .condNec(
        !state.fiberCommits.contains(cid),
        (),
        Errors.CidAlreadyExists(cid): DataApplicationValidationError
      )
      .pure[F]

  /** Validates that a CID exists (for update operations) */
  def cidIsFound[F[_]: Applicative](cid: UUID, state: OnChain): F[ValidationResult] =
    Validated
      .condNec(
        state.fiberCommits.contains(cid),
        (),
        Errors.CidNotFound(cid): DataApplicationValidationError
      )
      .pure[F]

  // ============== JsonLogic Value Rules ==============

  /** Validates that a value is a MapValue (required for state data) */
  def isMapValue[F[_]: Applicative](
    value:     JsonLogicValue,
    fieldName: String
  ): F[ValidationResult] =
    value match {
      case _: MapValue => ().validNec[DataApplicationValidationError].pure[F]
      case _           => (Errors.NotMapValue(fieldName): DataApplicationValidationError).invalidNec[Unit].pure[F]
    }

  /** Validates that an optional value is None, NullValue, or MapValue */
  def isMapValueOrNull[F[_]: Applicative](
    value:     Option[JsonLogicValue],
    fieldName: String
  ): F[ValidationResult] =
    value match {
      case None              => ().validNec[DataApplicationValidationError].pure[F]
      case Some(NullValue)   => ().validNec[DataApplicationValidationError].pure[F]
      case Some(_: MapValue) => ().validNec[DataApplicationValidationError].pure[F]
      case _ => (Errors.NotMapValueOrNull(fieldName): DataApplicationValidationError).invalidNec[Unit].pure[F]
    }

  /** Validates that a value is not NullValue */
  def isNotNull[F[_]: Applicative](
    value:     JsonLogicValue,
    fieldName: String
  ): F[ValidationResult] =
    value match {
      case NullValue => (Errors.ValueIsNull(fieldName): DataApplicationValidationError).invalidNec[Unit].pure[F]
      case _         => ().validNec[DataApplicationValidationError].pure[F]
    }

  // ============== Size Validation Rules ==============

  /** Validates that a JsonLogicValue doesn't exceed size limits */
  def valueWithinSizeLimit[F[_]: Applicative](
    value:        JsonLogicValue,
    maxSizeBytes: Long,
    fieldName:    String
  ): F[ValidationResult] = {
    val estimatedSize = estimateValueSize(value)
    Validated
      .condNec(
        estimatedSize <= maxSizeBytes,
        (),
        Errors.ValueTooLarge(fieldName, estimatedSize, maxSizeBytes): DataApplicationValidationError
      )
      .pure[F]
  }

  /** Validates payload structure (string lengths, array sizes, control chars) */
  def payloadStructureValid[F[_]: Applicative](
    payload:   JsonLogicValue,
    fieldName: String
  ): F[ValidationResult] = {
    val errors = validatePayloadStructure(payload, fieldName)
    errors match {
      case Nil    => ().validNec[DataApplicationValidationError].pure[F]
      case h :: t => cats.data.NonEmptyChain.of(h, t: _*).invalid[Unit].pure[F]
    }
  }

  // ============== Expression Depth Rules ==============

  /** Validates that an expression doesn't exceed maximum depth */
  def expressionWithinDepthLimit[F[_]: Applicative](
    expr:      JsonLogicExpression,
    fieldName: String,
    maxDepth:  Int = Limits.MaxExpressionDepth
  ): F[ValidationResult] =
    checkExpressionDepth(expr, 0, maxDepth) match {
      case Left(depth) =>
        (Errors.ExpressionTooDeep(fieldName, depth, maxDepth): DataApplicationValidationError)
          .invalidNec[Unit]
          .pure[F]
      case Right(_) =>
        ().validNec[DataApplicationValidationError].pure[F]
    }

  // ============== Private Helpers ==============

  private def estimateValueSize(value: JsonLogicValue, depth: Int = 0): Long = {
    if (depth > 100) return 100L // Prevent infinite recursion

    value match {
      case NullValue    => 4L
      case BoolValue(_) => 5L
      case IntValue(v)  => 8L + v.toString.length
      // FloatValue wraps an exact Ratio whose toString prints "n/d" (e.g. "1/3");
      // size it by the decimal rendering used on the wire (Json.fromBigDecimal)
      // so estimates stay consistent with the serialized payload.
      case FloatValue(v) => 8L + v.toBigDecimal.toString.length
      case StrValue(s)   => 4L + s.length.toLong * 2L // UTF-16 encoding estimate
      case ArrayValue(items) =>
        8L + items.map(estimateValueSize(_, depth + 1)).sum
      case MapValue(map) =>
        8L + map.toList.map { case (k, v) =>
          k.length * 2 + estimateValueSize(v, depth + 1)
        }.sum
      case FunctionValue(_) => 100L // Should not be in state
    }
  }

  private def validatePayloadStructure(
    value: JsonLogicValue,
    path:  String
  ): List[DataApplicationValidationError] =
    value match {
      case StrValue(s) if s.length > Limits.MaxStringLength =>
        List(Errors.StringTooLong(path, s.length, Limits.MaxStringLength))

      case StrValue(s) if s.exists(_.isControl) =>
        List(Errors.StringContainsControlChars(path))

      case ArrayValue(items) if items.size > Limits.MaxArraySize =>
        List(Errors.ArrayTooLarge(path, items.size, Limits.MaxArraySize))

      case ArrayValue(items) =>
        items.zipWithIndex.flatMap { case (item, idx) =>
          validatePayloadStructure(item, s"$path[$idx]")
        }

      case MapValue(map) if map.size > Limits.MaxMapSize =>
        List(Errors.MapTooLarge(path, map.size, Limits.MaxMapSize))

      case MapValue(map) =>
        map.toList.flatMap { case (k, v) =>
          validatePayloadStructure(v, s"$path.$k")
        }

      case _ => List.empty
    }

  private def checkExpressionDepth(
    expr:         JsonLogicExpression,
    currentDepth: Int,
    maxDepth:     Int
  ): Either[Int, Unit] =
    if (currentDepth > maxDepth) {
      Left(currentDepth)
    } else {
      expr match {
        case ApplyExpression(_, args) =>
          args.traverse(arg => checkExpressionDepth(arg, currentDepth + 1, maxDepth)).map(_ => ())

        case ArrayExpression(items) =>
          items.traverse(item => checkExpressionDepth(item, currentDepth + 1, maxDepth)).map(_ => ())

        case MapExpression(map) =>
          map.values.toList.traverse(v => checkExpressionDepth(v, currentDepth + 1, maxDepth)).map(_ => ())

        case VarExpression(Right(nested), _) =>
          checkExpressionDepth(nested, currentDepth + 1, maxDepth)

        case _ =>
          Right(())
      }
    }

  // ============== Errors ==============

  object Errors {

    final case class CidAlreadyExists(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Resource with ID $cid already exists"
    }

    final case class CidNotFound(cid: UUID) extends DataApplicationValidationError {
      override val message: String = s"Resource with ID $cid not found"
    }

    final case class NotMapValue(fieldName: String) extends DataApplicationValidationError {
      override val message: String = s"$fieldName must be a MapValue"
    }

    final case class NotMapValueOrNull(fieldName: String) extends DataApplicationValidationError {
      override val message: String = s"$fieldName must be a MapValue or null"
    }

    final case class ValueIsNull(fieldName: String) extends DataApplicationValidationError {
      override val message: String = s"$fieldName cannot be null"
    }

    final case class ValueTooLarge(fieldName: String, actualBytes: Long, maxBytes: Long)
        extends DataApplicationValidationError {
      override val message: String = s"$fieldName size ($actualBytes bytes) exceeds maximum ($maxBytes bytes)"
    }

    final case class ExpressionTooDeep(fieldName: String, depth: Int, maxDepth: Int)
        extends DataApplicationValidationError {
      override val message: String = s"$fieldName expression depth ($depth) exceeds maximum ($maxDepth)"
    }

    final case class StringTooLong(path: String, length: Int, maxLength: Int) extends DataApplicationValidationError {
      override val message: String = s"String at $path (length $length) exceeds maximum ($maxLength)"
    }

    final case class StringContainsControlChars(path: String) extends DataApplicationValidationError {
      override val message: String = s"String at $path contains control characters"
    }

    final case class ArrayTooLarge(path: String, size: Int, maxSize: Int) extends DataApplicationValidationError {
      override val message: String = s"Array at $path (size $size) exceeds maximum ($maxSize)"
    }

    final case class MapTooLarge(path: String, size: Int, maxSize: Int) extends DataApplicationValidationError {
      override val message: String = s"Map at $path (size $size) exceeds maximum ($maxSize)"
    }
  }
}
