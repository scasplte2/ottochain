package xyz.kd5ujc.schema.api

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The error body used by routes that construct their own `Response[F]` (state-proof 404/500, webhook 404).
 * Wire shape `{"error": "..."}` is preserved.
 *
 * NOTE: the typed half of the API emits the tessellation `DataApplicationValidationError` shape via
 * `.toResponse`. Unifying both onto a single envelope is RFC decision #1 — a verified follow-up, left out
 * of this pass to avoid guessing that shape.
 */
@derive(customizableEncoder, customizableDecoder)
final case class ErrorResponse(error: String)
