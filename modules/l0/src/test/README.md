# L0 Module Webhook Rejection Tests

**Card**: 🌐 Bridge: Dispatch rejection webhook events per-update (#69962948b9229744fe0f7609)  
**Status**: Implementation complete on main - these are validation/regression tests

## Overview

This test suite validates the rejection webhook system that dispatches ML0 rejection events to external webhook endpoints. The implementation is already complete on the main branch; these tests serve to validate the behavior and prevent regressions.

## Test Structure

### 1. WebhookDispatcherRejectionSuite
**Location**: `com.ottochain.webhooks.WebhookDispatcherRejectionSuite`  
**Purpose**: Unit tests for WebhookDispatcher.dispatchRejection() method

**Test Cases**:
- ✅ Dispatch rejection event with correct payload structure
- ✅ Extract fiberId from DataUpdate correctly  
- ✅ Generate deterministic updateHash from DataUpdate content
- ✅ Extract multiple signers from DataUpdate proofs
- ✅ Skip dispatch when webhook URL not configured
- ✅ Handle webhook endpoint errors gracefully (fire-and-forget)
- ✅ Avoid duplicate dispatches for same updateHash within ordinal
- ✅ Allow dispatches for different ordinals with same updateHash
- ✅ Handle multiple error types in single rejection

### 2. ML0ServiceRejectionSuite  
**Location**: `com.ottochain.services.ML0ServiceRejectionSuite`  
**Purpose**: Integration tests for ML0Service webhook dispatch during validation

**Test Cases**:
- ✅ Dispatch webhook for each rejected DataUpdate during validation
- ✅ Skip webhook dispatch for valid DataUpdates
- ✅ Handle mixed batches with both valid and invalid updates  
- ✅ Continue validation when webhook dispatcher is None
- ✅ Maintain combineAll behavior when dispatching per-update rejections
- ✅ Implement fire-and-forget timing for webhook dispatch
- ✅ Preserve transaction validation semantics with webhook integration

### 3. RejectionWebhookE2ESuite
**Location**: `com.ottochain.integration.RejectionWebhookE2ESuite`  
**Purpose**: End-to-end tests for complete rejection notification flow

**Test Cases**:
- ✅ Propagate invalid transaction rejection to indexer
- ✅ Prevent duplicate rejection entries for same updateHash within ordinal
- ✅ Preserve rejection history across ordinal boundaries
- ✅ Handle webhook endpoint failures gracefully without blocking ML0
- ✅ Support querying rejection history via indexer API

## Test Data Model

### RejectionEventPayload
```scala
case class RejectionEventPayload(
  ordinal: Long,
  fiberId: String, 
  updateHash: String,
  signers: List[String],
  errorCode: String,
  reason: String
)
```

### ValidationError
```scala
case class ValidationError(code: String, message: String)
```

## Running Tests

### Unit Tests
```bash
# Run all L0 webhook tests
sbt "project l0" test

# Run only WebhookDispatcher tests  
sbt "project l0" "testOnly *WebhookDispatcherRejectionSuite"

# Run only ML0Service tests
sbt "project l0" "testOnly *ML0ServiceRejectionSuite"
```

### Integration Tests
```bash
# Run E2E tests (requires running services)
sbt "project l0" it:test

# Run specific E2E suite
sbt "project l0" "it:testOnly *RejectionWebhookE2ESuite"
```

### Custom Test Tasks
```bash
# Run webhook-specific tests only
sbt "project l0" testWebhooks

# Run E2E integration tests only  
sbt "project l0" testE2E
```

## Environment Configuration

### Required Environment Variables
- `WEBHOOK_URL`: Target webhook endpoint (default: http://localhost:3030/webhooks/rejections)
- `WEBHOOK_TIMEOUT`: Webhook timeout duration (default: 30s)
- `TEST_MODE`: Enable test mode (default: true)

### E2E Test Requirements
- `METAGRAPH_URL`: ML0 metagraph endpoint (default: http://localhost:4000)
- `INDEXER_URL`: Indexer API endpoint (default: http://localhost:3031)  
- `BRIDGE_URL`: Bridge API endpoint (default: http://localhost:3030)

## Implementation Details

### Webhook Dispatch Flow
1. **ML0Service.validateData()** calls validateSignedUpdate() for each DataUpdate
2. **Per-update validation** collects Left(ValidationError) results
3. **Fire-and-forget dispatch** calls WebhookDispatcher.dispatchRejection() 
4. **WebhookDispatcher** constructs RejectionEventPayload and POSTs to configured webhook URL
5. **Deduplication** prevents duplicate dispatches for same updateHash within ordinal

### Key Design Principles
- **Fire-and-forget**: Webhook failures don't block ML0 validation
- **Per-update granularity**: Each rejected DataUpdate gets individual webhook
- **Ordinal-based deduplication**: Same updateHash allowed across different ordinals
- **Deterministic hashing**: UpdateHash generated from consistent DataUpdate content

### Security Considerations
- Webhook URL configuration via environment variable only
- No sensitive data in webhook payloads (only hashes and error codes)
- Fire-and-forget prevents webhook endpoints from DoS attacks on ML0
- Signers extracted from cryptographic proofs, not user-controlled fields

## Debugging

### Common Issues
1. **Tests fail with "webhook not configured"**: Set `WEBHOOK_URL` environment variable
2. **E2E tests timeout**: Ensure all required services (metagraph, indexer, bridge) are running
3. **Duplicate dispatch tests fail**: Check deduplication logic in WebhookDispatcher
4. **Fire-and-forget timing fails**: Webhook dispatch should not block validation

### Useful Debug Commands
```bash
# Check webhook configuration
curl -X GET http://localhost:4000/config/webhook

# Manually trigger webhook test
curl -X POST http://localhost:3030/webhooks/rejections \
  -H "Content-Type: application/json" \
  -d '{"ordinal":1000,"fiberId":"test","updateHash":"sha256:test","signers":["DAG123"],"errorCode":"TEST","reason":"test"}'

# Check indexer rejection history
curl -X GET http://localhost:3031/rejections
```

## Dependencies

- `cats-effect`: Async IO operations
- `http4s-client`: HTTP client for webhook dispatch  
- `circe`: JSON encoding/decoding for payloads
- `scalatest`: Test framework
- `cats-effect-testing-scalatest`: Async test support
- `shared-test`: E2E test helpers (IndexerHelpers, MetagraphHelpers, WebhookHelpers)

## Related Documentation

- [Rejection Notifications Epic](https://trello.com/c/6987b6201dd2b3da6b7741e8)
- [WebhookDispatcher API](../../../main/scala/com/ottochain/l0/modules/shared/services/WebhookDispatcher.scala) 
- [ML0Service Integration](../../../main/scala/com/ottochain/services/ML0Service.scala)
- [Bridge Webhook Handler](../../../bridge/src/main/routes/webhooks.scala)