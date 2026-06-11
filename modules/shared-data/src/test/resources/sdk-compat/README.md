# SDK Compatibility Reference

This directory documents the JSON wire format contract between the Scala metagraph
and the TypeScript SDK (`@ottochain/sdk`).

## Source of Truth

The **Scala metagraph** is the source of truth for wire format.
The SDK must adapt to match the Scala encoding.

## Wire Format Rules

| Concept | Scala Type | JSON Wire Format |
|---------|-----------|-----------------|
| OttochainMessage | sealed trait | `{"MessageName": {...fields}}` |
| UUID/FiberId | java.util.UUID | `"550e8400-e29b-41d4-a716-446655440000"` |
| FiberOrdinal | case class wrapping NonNegLong | `42` (plain integer) |
| StateId | case class wrapping String | `{"value": "idle"}` (wrapped object) |
| AccessControlPolicy | sealed trait | `{"Public": {}}` / `{"Whitelist": {"addresses": [...]}}` |
| Option[T] = None | Scala Option | absent key or `null` |

## Known SDK ↔ Scala Discrepancies

### StateId as plain string vs. wrapped object

**SDK types.ts** documents `StateMachineDefinition.initialState` as `string`.
**Actual Scala wire format** is `{"value": "idle"}` (wrapped object).

This discrepancy exists because `StateId` is a single-field case class and
circe-magnolia does not auto-unwrap single-field case classes. Clients must send
the wrapped form. The SDK types.ts comment is incorrect and should be updated.

## SDK Version Tracking

When the SDK is updated, run `SdkCompatibilitySuite` to verify no breaking changes.
If a test fails after an SDK update:
1. Check if the Scala model or codec changed
2. If Scala changed (intentionally): update these docs + SDK TypeScript types
3. If SDK changed: the SDK change is incompatible with the current metagraph

## Manual Verification (CI override)

To test against a specific SDK version or branch:
```bash
# Point to SDK repo
export OTTOCHAIN_SDK_REF=v1.3.0  # or a git SHA

# Regenerate test fixtures (future: automated)
# cd path/to/ottochain-sdk && npm run generate-fixtures
```

Currently fixtures are maintained manually in `SdkCompatibilitySuite.scala`.
