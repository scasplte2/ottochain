package xyz.kd5ujc.schema.fiber

/**
 * THE canonical nullifier-value normalizer (protocol-nullifier-set.md). A nullifier is the 64-char lowercase
 * hex rendering of a Hash; clients may author it with or without a `0x` prefix and in any case. Every surface
 * that accepts an nf value (the `_consumeNullifier` extractor, the offline linter, the `/v1/nullifiers` route)
 * MUST normalize through here so the committed `nullifier/<domain>/<nf>` key is byte-identical everywhere.
 *
 * Normalization: strip one optional leading `0x`/`0X`, lowercase, then require EXACTLY 64 chars of
 * `[0-9a-f]`. Anything else is `None` — the caller decides its loud-rejection mode (graceful
 * `CombineRejected` in the extractor, BadRequest on the route, advisory diagnostic in the linter).
 *
 * The 64-hex guarantee is also what keeps the committed-key derivation TOTAL: the `<nf>` segment is exactly
 * 64 chars of the CommitKey charset, so `CommitKey.unsafe(s"nullifier/$domain/$nf")` can never throw.
 */
object NullifierHex {

  private val HexPattern = "^[0-9a-f]{64}$".r

  /** `Some(normalized 64-hex)` or `None` when the value cannot be a nullifier. */
  def normalize(raw: String): Option[String] = {
    val stripped = if (raw.length >= 2 && (raw.startsWith("0x") || raw.startsWith("0X"))) raw.drop(2) else raw
    val lowered = stripped.toLowerCase
    if (HexPattern.matches(lowered)) Some(lowered) else None
  }
}
