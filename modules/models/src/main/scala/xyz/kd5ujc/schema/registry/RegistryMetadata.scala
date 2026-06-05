package xyz.kd5ujc.schema.registry

/**
 * Bounds for a [[RegistryEntry]]'s optional free-form metadata "notes" map (off-chain links + descriptors,
 * e.g. `repo` / `homepage` / `license` / `description`). Deliberately NOT a typed key-whitelist: the chain
 * enforces only what protects consensus — a small entry count, key/value length caps, and no control
 * characters — and stays agnostic to the *meaning*. Semantic validation ("is `repo` a real URL?",
 * profanity, etc.) lives OFF-CHAIN at the Bridge, the same split as name eligibility. Extensible by
 * construction: any key is allowed within the bounds.
 *
 * One pure [[validate]] is shared by the combiner (authoritative abort) and the validator (early
 * structured rejection), so the two never drift.
 */
object RegistryMetadata {

  val MaxEntries: Int = 8 // a small, bounded notes map (a few free-form fields, not a typed schema)
  val MaxKeyLength: Int = 32
  val MaxValueLength: Int = 128

  /** Validate the notes map: entry count + key/value length + printable (no control characters). */
  def validate(metadata: Map[String, String]): Either[String, Unit] = {
    val problems =
      (if (metadata.size > MaxEntries) List(s"too many metadata entries (${metadata.size} > $MaxEntries)") else Nil) :::
      metadata.toList.sortBy(_._1).flatMap { case (k, v) =>
        (if (k.trim.isEmpty) List("a metadata key is empty") else Nil) :::
        (if (k.length > MaxKeyLength) List(s"metadata key '$k' exceeds $MaxKeyLength chars") else Nil) :::
        (if (v.length > MaxValueLength) List(s"metadata value for '$k' exceeds $MaxValueLength chars") else Nil) :::
        (if (hasControl(k) || hasControl(v)) List(s"metadata '$k' contains control characters") else Nil)
      }
    if (problems.isEmpty) Right(()) else Left(problems.mkString("; "))
  }

  private def hasControl(s: String): Boolean = s.exists(_.isControl)
}
