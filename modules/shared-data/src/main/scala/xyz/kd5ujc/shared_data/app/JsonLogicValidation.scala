package xyz.kd5ujc.shared_data.app

import io.circe.Json

/**
 * Basic JSON Logic validation trait for asset state machine integration.
 *
 * This provides a simplified interface for JSON Logic evaluation that can be
 * extended with the full JLVM implementation as needed.
 */
trait JsonLogicValidation {

  /**
   * Evaluate a JSON Logic expression with given context
   *
   * @param expression The JSON Logic expression to evaluate
   * @param context The variable context for evaluation
   * @return The evaluation result, or None if evaluation fails
   */
  def evaluateJsonLogic(expression: Json, context: Map[String, Json]): Option[Json] =
    // This is a basic implementation for asset state machine development
    // In production, this should integrate with the full JSON Logic VM

    expression.asObject match {
      case Some(obj) if obj.contains("==") =>
        evaluateEquality(obj("=="), context)

      case Some(obj) if obj.contains("!=") =>
        evaluateEquality(obj("!="), context).map(result => Json.fromBoolean(!result.asBoolean.getOrElse(false)))

      case Some(obj) if obj.contains("var") =>
        evaluateVariable(obj("var"), context)

      case Some(obj) if obj.contains("merge") =>
        evaluateMerge(obj("merge"), context)

      case Some(obj) if obj.contains(">=") =>
        evaluateComparison(obj(">="), context, _ >= _)

      case Some(obj) if obj.contains("!!") =>
        evaluateTruthy(obj("!!"), context)

      case Some(obj) if obj.contains("!==") =>
        evaluateStrictInequality(obj("!=="), context)

      case Some(obj) if obj.contains("===") =>
        evaluateStrictEquality(obj("==="), context)

      case Some(obj) if obj.contains("log") =>
        // Log operation - just return the message
        obj("log")

      case _ =>
        // Return the expression as-is if we can't evaluate it
        Some(expression)
    }

  /**
   * Evaluate equality operator
   */
  private def evaluateEquality(args: Option[Json], context: Map[String, Json]): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 2).flatMap { array =>
      for {
        left  <- evaluateExpression(array(0), context)
        right <- evaluateExpression(array(1), context)
      } yield Json.fromBoolean(left == right)
    }

  /**
   * Evaluate strict equality operator
   */
  private def evaluateStrictEquality(args: Option[Json], context: Map[String, Json]): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 2).flatMap { array =>
      for {
        left  <- evaluateExpression(array(0), context)
        right <- evaluateExpression(array(1), context)
      } yield Json.fromBoolean(left == right)
    }

  /**
   * Evaluate strict inequality operator
   */
  private def evaluateStrictInequality(args: Option[Json], context: Map[String, Json]): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 2).flatMap { array =>
      for {
        left  <- evaluateExpression(array(0), context)
        right <- evaluateExpression(array(1), context)
      } yield Json.fromBoolean(left != right)
    }

  /**
   * Evaluate variable lookup
   */
  private def evaluateVariable(varExpr: Option[Json], context: Map[String, Json]): Option[Json] =
    varExpr.flatMap(_.asString).flatMap { varName =>
      if (varName.contains(".")) {
        // Handle nested variable access like "state.owner"
        val parts = varName.split("\\.")
        parts.toList match {
          case root :: path =>
            context.get(root).flatMap(navigateJsonPath(_, path))
          case Nil => None
        }
      } else {
        context.get(varName)
      }
    }

  /**
   * Navigate a JSON path
   */
  private def navigateJsonPath(json: Json, path: List[String]): Option[Json] =
    path match {
      case Nil => Some(json)
      case head :: tail =>
        json.asObject.flatMap(_.apply(head)).flatMap(navigateJsonPath(_, tail))
    }

  /**
   * Evaluate merge operation
   */
  private def evaluateMerge(args: Option[Json], context: Map[String, Json]): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 2).flatMap { array =>
      for {
        base    <- evaluateExpression(array(0), context)
        updates <- evaluateExpression(array(1), context)
        merged  <- mergeJsonObjects(base, updates)
      } yield merged
    }

  /**
   * Evaluate comparison operations
   */
  private def evaluateComparison(
    args:    Option[Json],
    context: Map[String, Json],
    op:      (Double, Double) => Boolean
  ): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 2).flatMap { array =>
      for {
        left     <- evaluateExpression(array(0), context)
        right    <- evaluateExpression(array(1), context)
        leftNum  <- left.asNumber.map(_.toDouble)
        rightNum <- right.asNumber.map(_.toDouble)
      } yield Json.fromBoolean(op(leftNum, rightNum))
    }

  /**
   * Evaluate truthy operation (!!)
   */
  private def evaluateTruthy(args: Option[Json], context: Map[String, Json]): Option[Json] =
    args.flatMap(_.asArray).filter(_.length == 1).flatMap { array =>
      evaluateExpression(array(0), context).map { value =>
        val isTruthy = value match {
          case json if json.isNull => false
          case json =>
            json.asBoolean.getOrElse(
              json.asString.exists(_.nonEmpty) ||
              json.asNumber.exists(!_.toDouble.equals(0.0)) ||
              json.asArray.exists(_.nonEmpty) ||
              json.asObject.exists(_.nonEmpty)
            )
        }
        Json.fromBoolean(isTruthy)
      }
    }

  /**
   * Evaluate a single expression (variable lookup or literal)
   */
  private def evaluateExpression(expr: Json, context: Map[String, Json]): Option[Json] =
    expr.asObject match {
      case Some(obj) if obj.contains("var") =>
        evaluateVariable(obj("var"), context)
      case _ =>
        Some(expr)
    }

  /**
   * Merge two JSON objects
   */
  private def mergeJsonObjects(base: Json, updates: Json): Option[Json] =
    for {
      baseObj    <- base.asObject
      updatesObj <- updates.asObject
    } yield Json.fromJsonObject(baseObj.deepMerge(updatesObj))
}
