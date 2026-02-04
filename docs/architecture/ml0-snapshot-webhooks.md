# ML0 Snapshot Webhook Notifications

Push-based notifications from ML0 to external subscribers when snapshot consensus completes, enabling real-time indexing without polling.

## API Endpoints

All endpoints are prefixed with `/data-application` per Tessellation's data application routing convention.

### Subscribe

Register a webhook callback URL to receive snapshot notifications.

```
POST /data-application/v1/webhooks/subscribe
Content-Type: application/json

{
  "callbackUrl": "https://your-service.com/webhook",
  "secret": "optional-hmac-secret"
}
```

**Response (201 Created):**
```json
{
  "id": "sub_abc123",
  "callbackUrl": "https://your-service.com/webhook",
  "createdAt": "2026-02-03T21:30:00Z"
}
```

### Unsubscribe

Remove a webhook subscription.

```
DELETE /data-application/v1/webhooks/subscribe/:id
```

**Response:** 204 No Content (success) or 404 Not Found

### List Subscribers

List all active webhook subscribers.

```
GET /data-application/v1/webhooks/subscribers
```

**Response:**
```json
{
  "subscribers": [
    {
      "id": "sub_abc123",
      "callbackUrl": "https://your-service.com/webhook",
      "secret": "***",
      "active": true,
      "createdAt": "2026-02-03T21:30:00Z",
      "lastDeliveryAt": "2026-02-03T22:00:00Z",
      "failCount": 0
    }
  ]
}
```

## Webhook Payload

When snapshot consensus completes, ML0 sends a POST request to each registered callback URL.

**Event:** `snapshot.finalized`

```json
{
  "event": "snapshot.finalized",
  "ordinal": 12345,
  "hash": "abc123def456...",
  "timestamp": "2026-02-03T21:30:00.123456789Z",
  "metagraphId": "DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG",
  "stats": {
    "updatesProcessed": 42,
    "stateMachinesActive": 156,
    "scriptsActive": 23
  }
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `event` | string | Always `"snapshot.finalized"` |
| `ordinal` | number | Snapshot ordinal (sequential, starts at 1) |
| `hash` | string | SHA-256 hash of the snapshot content |
| `timestamp` | string | ISO 8601 timestamp of consensus completion |
| `metagraphId` | string | DAG address identifying this metagraph |
| `stats.updatesProcessed` | number | Count of data updates in this snapshot |
| `stats.stateMachinesActive` | number | Count of state machines with Active status |
| `stats.scriptsActive` | number | Count of scripts with Active status |

## HMAC Signature Verification

If a `secret` was provided during subscription, the webhook includes an HMAC-SHA256 signature header for verification.

**Header:** `X-Webhook-Signature: sha256=<hex-encoded-signature>`

### Verification (Node.js example)

```typescript
import { createHmac, timingSafeEqual } from 'crypto';

function verifySignature(body: string, signature: string, secret: string): boolean {
  const [algorithm, hash] = signature.split('=');
  if (algorithm !== 'sha256') return false;
  
  const expected = createHmac('sha256', secret)
    .update(body)
    .digest('hex');
  
  return timingSafeEqual(Buffer.from(hash), Buffer.from(expected));
}
```

## Failure Handling

- **Delivery timeout:** 10 seconds per request
- **Auto-deactivation:** Subscriber marked inactive after 5 consecutive failures
- **Fire-and-forget:** Delivery failures don't block snapshot consensus
- **No retries:** Failed deliveries are logged but not retried (indexer should handle gaps)

## Integration Pattern

```
┌─────────────┐     POST       ┌──────────────┐
│   ML0       │ ──────────────►│   Indexer    │
│  (snapshot) │                │  (webhook)   │
└─────────────┘                └──────┬───────┘
                                      │
                                      ▼
                               ┌──────────────┐
                               │   Postgres   │
                               │ (projection) │
                               └──────────────┘
```

1. Indexer registers webhook at startup
2. ML0 pushes notifications on each snapshot consensus
3. Indexer fetches full snapshot data from ML0 if needed
4. Indexer updates Postgres read projection
5. Gateway queries Postgres for client requests

## Example: Indexer Webhook Handler

```typescript
app.post('/webhook', async (req, res) => {
  // Verify HMAC signature
  const signature = req.headers['x-webhook-signature'];
  if (!verifySignature(JSON.stringify(req.body), signature, WEBHOOK_SECRET)) {
    return res.status(401).json({ error: 'Invalid signature' });
  }

  const { event, ordinal, hash, stats } = req.body;
  
  if (event === 'snapshot.finalized') {
    // Fetch full snapshot if we have updates to process
    if (stats.updatesProcessed > 0) {
      const snapshot = await ml0Client.getSnapshot(ordinal);
      await processSnapshot(snapshot);
    }
    
    // Update watermark
    await db.updateLastProcessedOrdinal(ordinal);
  }

  res.status(200).json({ received: true });
});
```

## See Also

- [Service Architecture](./service-architecture.md) — Full backend architecture with Indexer integration
- [ML0CustomRoutes.scala](../../modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0CustomRoutes.scala) — API implementation
- [WebhookDispatcher.scala](../../modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/webhooks/WebhookDispatcher.scala) — Dispatch logic
