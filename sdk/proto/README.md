# SDK Proto Definitions

TypeScript-compatible protobuf definitions for OttoChain types.

## ⚠️ Keep Aligned with Scala Protos

These proto files are **mirrors** of `modules/proto/src/main/protobuf/ottochain/v1/`:

| SDK (TypeScript) | Scala |
|------------------|-------|
| `sdk/proto/ottochain/v1/*.proto` | `modules/proto/src/main/protobuf/ottochain/v1/*.proto` |

**Differences:**
- SDK protos omit `scalapb/scalapb.proto` and `validate/validate.proto` imports
- SDK protos omit `(scalapb.options)` and `(validate.rules)` annotations
- Both define the same messages, fields, and types

**When updating:**
1. Edit the Scala proto files first (source of truth)
2. Copy changes to SDK protos, stripping Scala-specific annotations
3. Run `npm run generate:proto` in sdk/
4. Run `sbt proto/compile` in project root

## Regenerating TypeScript

```bash
cd sdk
npm run generate:proto
```

This uses `ts-proto` to generate TypeScript interfaces in `src/generated/`.

## Why Two Proto Directories?

- **Scala** needs ScalaPB annotations for case class generation + validation
- **TypeScript** can't parse those imports (protoc fails)
- Keeping them separate is simpler than build-time stripping

A sync script could be added later if drift becomes a problem.
