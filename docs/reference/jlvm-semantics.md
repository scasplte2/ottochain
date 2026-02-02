# JLVM Semantics — Metakit JSON Logic Virtual Machine

This document describes the complete semantics of Metakit's JSON Logic VM (JLVM), with particular focus on how JSON is parsed into the internal AST and how expressions are evaluated. Understanding these details is critical for agents (human or AI) that need to programmatically generate valid state machine definitions.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [The AST: JsonLogicExpression](#the-ast-jsonlogicexpression)
3. [JSON Parsing to AST](#json-parsing-to-ast)
4. [The Value System: JsonLogicValue](#the-value-system-jsonlogicvalue)
5. [Variable Resolution](#variable-resolution)
6. [Operator Catalog](#operator-catalog)
7. [Evaluation Strategies](#evaluation-strategies)
8. [Numeric Semantics](#numeric-semantics)
9. [Coercion Rules](#coercion-rules)
10. [Callback Operators (Higher-Order)](#callback-operators-higher-order)
11. [Key Differences from Standard JSON Logic](#key-differences-from-standard-json-logic)
12. [Gotchas for AI Agent Generation](#gotchas-for-ai-agent-generation)

---

## Architecture Overview

The JLVM consists of six core components:

| Component | File | Purpose |
|-----------|------|---------|
| `JsonLogicExpression` | Expression AST | The parsed representation of a JSON Logic program |
| `JsonLogicValue` | Value types | Runtime values (Int, Float, Bool, Str, Array, Map, Null, Function) |
| `JsonLogicOp` | Operator enum | All 60+ supported operators |
| `JsonLogicSemantics` | Operator implementations | How each operator behaves given arguments |
| `JsonLogicRuntime` | Evaluation engine | Recursive and tail-recursive interpreters |
| `JsonLogicEvaluator` | Public API | Entry point for evaluation |

```
JSON string
    │
    ▼ (Circe Decoder)
JsonLogicExpression (AST)
    │
    ▼ (JsonLogicRuntime.evaluate)
JsonLogicValue (result)
```

## The AST: JsonLogicExpression

Every JSON Logic program is decoded into one of five AST node types:

```scala
sealed trait JsonLogicExpression

// Operator application: {"op": [arg1, arg2, ...]}
final case class ApplyExpression(op: JsonLogicOp, args: List[JsonLogicExpression])

// Literal value: 42, "hello", true, null
final case class ConstExpression(value: JsonLogicValue)

// Array literal: [expr1, expr2, ...]
final case class ArrayExpression(value: List[JsonLogicExpression])

// Object literal (non-operator): {"key1": expr1, "key2": expr2}
final case class MapExpression(value: Map[String, JsonLogicExpression])

// Variable reference: {"var": "path.to.value"}
final case class VarExpression(value: Either[String, JsonLogicExpression], default: Option[JsonLogicValue])
```

**Key insight:** A `MapExpression` is what you get when you write an object where the keys are NOT operators. This is how OttoChain's effects work — each key in the effect becomes a field in the new state:

```json
{
  "balance": {"+": [{"var": "state.balance"}, {"var": "event.amount"}]},
  "name": {"var": "state.name"}
}
```

This parses as:
```
MapExpression({
  "balance" -> ApplyExpression(AddOp, [VarExpression("state.balance"), VarExpression("event.amount")]),
  "name" -> VarExpression("state.name")
})
```

## JSON Parsing to AST

The decoder (`JsonLogicExpression.decodeJsonLogicExpr`) handles JSON by type:

### Primitives → ConstExpression

| JSON | AST |
|------|-----|
| `null` | `ConstExpression(NullValue)` |
| `true` / `false` | `ConstExpression(BoolValue(b))` |
| `42` | `ConstExpression(IntValue(42))` |
| `3.14` | `ConstExpression(FloatValue(3.14))` |
| `"hello"` | `ConstExpression(StrValue("hello"))` |

**Number parsing detail:** Numbers are first converted to `BigDecimal`. If `toBigIntExact` succeeds, it becomes `IntValue`; otherwise `FloatValue`. This means `1.0` becomes `FloatValue`, not `IntValue`.

### Strings → ConstExpression(StrValue)

All strings are literal values. There is no implicit variable resolution — you must use `{"var": "path"}` explicitly.

### Arrays → ArrayExpression or ApplyExpression

Arrays are parsed with a priority check:

1. **Array-syntax operators:** If the first element is a string matching a known operator tag, it's parsed as an operator application:
   ```json
   ["+", 1, 2]  →  ApplyExpression(AddOp, [ConstExpression(IntValue(1)), ConstExpression(IntValue(2))])
   ```

2. **Array-syntax var:** If the first element is `"var"`, it's a variable reference:
   ```json
   ["var", "state.balance"]  →  VarExpression(Left("state.balance"))
   ```

3. **Regular array:** Otherwise, each element is recursively parsed as an expression:
   ```json
   [1, "two", true]  →  ArrayExpression([ConstExpression(IntValue(1)), ConstExpression(StrValue("two")), ConstExpression(BoolValue(true))])
   ```

4. **Fallback:** If expression parsing fails for any element, try parsing the whole thing as `ConstExpression(ArrayValue(...))`.

### Objects → VarExpression, ApplyExpression, or MapExpression

Objects are the most complex case, parsed by checking the key:

1. **Empty object `{}`** → `ConstExpression(MapValue.empty)`

2. **Single key `""` (empty string)** → `VarExpression` — this is the "identity var" that returns the entire context:
   ```json
   {"": ""}  →  VarExpression(Left(""))
   ```

3. **Single key `"var"`** → `VarExpression`:
   ```json
   {"var": "state.balance"}  →  VarExpression(Left("state.balance"))
   {"var": ["state.x", 0]}  →  VarExpression(Left("state.x"), default=Some(IntValue(0)))
   {"var": {"cat": [{"var": "event.prefix"}, ".name"]}}  →  VarExpression(Right(ApplyExpression(CatOp, ...)))
   ```

4. **Single key matching a known operator** → `ApplyExpression`:
   ```json
   {"+": [1, 2]}  →  ApplyExpression(AddOp, [ConstExpression(IntValue(1)), ConstExpression(IntValue(2))])
   {"==": [{"var": "x"}, 5]}  →  ApplyExpression(EqOp, [VarExpression(Left("x")), ConstExpression(IntValue(5))])
   ```
   
   **Argument wrapping:** If the operator value is a single expression (not an array), it's wrapped in a single-element list:
   ```json
   {"!": {"var": "locked"}}  →  ApplyExpression(NotOp, [VarExpression(Left("locked"))])
   ```

5. **Multi-key object** → `MapExpression`:
   ```json
   {"a": 1, "b": {"var": "x"}}  →  MapExpression({"a" -> ConstExpression(IntValue(1)), "b" -> VarExpression(Left("x"))})
   ```
   
   Each value is recursively decoded as `JsonLogicExpression`. If that fails, falls back to `ConstExpression(MapValue(...))`.

### VarExpression Parsing Details

The `var` operator supports multiple forms:

| JSON | Parsed As |
|------|-----------|
| `{"var": "state.x"}` | `VarExpression(Left("state.x"), None)` |
| `{"var": ""}` | `VarExpression(Left(""), None)` — returns entire context |
| `{"var": 0}` | `VarExpression(Left("0"), None)` — numeric keys are stringified |
| `{"var": ["state.x", 42]}` | `VarExpression(Left("state.x"), Some(IntValue(42)))` — with default |
| `{"var": {"+": [...]}}` | `VarExpression(Right(ApplyExpression(...)), None)` — computed path |

**Computed variable paths** allow dynamic property access — the inner expression is evaluated first, and its string result is used as the variable path.

## The Value System: JsonLogicValue

Runtime values form a sealed hierarchy:

```
JsonLogicValue
├── NullValue                          (singleton)
├── JsonLogicPrimitive
│   ├── BoolValue(value: Boolean)
│   ├── IntValue(value: BigInt)        ← arbitrary precision integers
│   ├── FloatValue(value: BigDecimal)  ← arbitrary precision decimals
│   └── StrValue(value: String)
├── JsonLogicCollection
│   ├── ArrayValue(value: List[JsonLogicValue])
│   └── MapValue(value: Map[String, JsonLogicValue])
└── FunctionValue(expr: JsonLogicExpression)  ← callback expressions
```

### Truthiness Rules

| Value | Truthy? |
|-------|---------|
| `NullValue` | false |
| `BoolValue(false)` | false |
| `BoolValue(true)` | true |
| `IntValue(0)` | false |
| `IntValue(n)` where n ≠ 0 | true |
| `FloatValue(0.0)` | false |
| `FloatValue(n)` where n ≠ 0 | true |
| `StrValue("")` | false |
| `StrValue(s)` where s ≠ "" | true |
| `ArrayValue([])` | false |
| `ArrayValue([...])` | true |
| `MapValue({})` | false |
| `MapValue({...})` | true |
| `FunctionValue(_)` | false |

These rules match JavaScript semantics and are critical for guards — a guard must evaluate to a truthy value for the transition to fire.

## Variable Resolution

Variable paths use dot-notation with segment-by-segment traversal:

```
getVar("state.data.items.0.name", context)
```

Resolution:
1. Split path by `.` → `["state", "data", "items", "0", "name"]`
2. Start with the context value (optionally merged with an extra context)
3. For each segment:
   - If current value is `MapValue`: look up key → continue or `NullValue` if missing
   - If current value is `ArrayValue`: parse segment as integer index → continue or `NullValue` if out of bounds
   - Otherwise: return `NullValue`

**Context merging:** When an extra context is provided (e.g., during `map`/`filter` callbacks), it is combined with the base variables:
- `MapValue + MapValue` → merged (extra overrides base)
- `ArrayValue + ArrayValue` → concatenated
- Primitives ignore the extension

**Empty key (`""`):** Returns the entire context value — useful in `map`/`filter` callbacks where the element IS the context.

**Trailing dot:** Any path ending in `.` returns `NullValue`.

## Operator Catalog

### Control Flow
| Op | Tag | Args | Description |
|----|-----|------|-------------|
| `IfElseOp` | `if` | `[cond, then, cond2, then2, ..., else]` | Lazy if/else chain (odd arg count required) |
| `DefaultOp` | `default` | `[val1, val2, ...]` | First truthy, non-null value |
| `NoOp` | `noop` | — | Internal, always errors |

### Logical
| Op | Tag | Args | Description |
|----|-----|------|-------------|
| `NotOp` | `!` | `[val]` | Logical NOT (returns `BoolValue(!truthy)`) |
| `NOp` | `!!` | `[val]` | Double-not / truthiness cast |
| `OrOp` | `or` | `[val1, val2, ...]` | First truthy value, or last value |
| `AndOp` | `and` | `[val1, val2, ...]` | Last truthy value if all truthy, or first falsy |

**Note:** `or` and `and` return the *actual value*, not just a boolean. `{"or": [0, "hello"]}` returns `"hello"`, not `true`.

### Comparison
| Op | Tag | Description |
|----|-----|-------------|
| `EqOp` | `==` | Loose equality with type coercion |
| `EqStrictOp` | `===` | Strict equality (same type required) |
| `NEqOp` | `!=` | Loose inequality |
| `NEqStrictOp` | `!==` | Strict inequality |
| `Lt` | `<` | Less than (supports 2 or 3 args for range: `a < b < c`) |
| `Leq` | `<=` | Less than or equal (supports range) |
| `Gt` | `>` | Greater than |
| `Geq` | `>=` | Greater than or equal |

### Arithmetic
| Op | Tag | Description |
|----|-----|-------------|
| `AddOp` | `+` | Sum (variadic). Single string arg → numeric conversion |
| `MinusOp` | `-` | Subtraction (binary) or negation (unary) |
| `TimesOp` | `*` | Product (variadic) |
| `DivOp` | `/` | Division (errors on zero) |
| `ModuloOp` | `%` | Modulo (errors on zero) |
| `MaxOp` | `max` | Maximum of list |
| `MinOp` | `min` | Minimum of list |
| `AbsOp` | `abs` | Absolute value |
| `RoundOp` | `round` | Round (HALF_UP) |
| `FloorOp` | `floor` | Floor |
| `CeilOp` | `ceil` | Ceiling |
| `PowOp` | `pow` | Power (exponent capped at 999) |

### Array
| Op | Tag | Description |
|----|-----|-------------|
| `MapOp` | `map` | `[array, callback]` — transform each element |
| `FilterOp` | `filter` | `[array, callback]` — keep truthy elements |
| `ReduceOp` | `reduce` | `[array, callback, init?]` — fold left |
| `AllOp` | `all` | `[array, callback]` — true if all truthy |
| `SomeOp` | `some` | `[array, callback, min?]` — true if ≥ min truthy (default 1) |
| `NoneOp` | `none` | `[array, callback]` — true if none truthy |
| `FindOp` | `find` | `[array, callback]` — first truthy element or null |
| `CountOp` | `count` | `[array]` or `[array, callback]` — count (matching) elements |
| `MergeOp` | `merge` | Concatenate arrays, or merge maps (later keys override) |
| `InOp` | `in` | `[value, array_or_string]` — membership/substring test |
| `IntersectOp` | `intersect` | `[array1, array2]` — true if all of array1 in array2 |
| `UniqueOp` | `unique` | `[array]` — deduplicate |
| `SliceOp` | `slice` | `[array, start, end?]` — slice (negative indices supported) |
| `ReverseOp` | `reverse` | `[array]` — reverse |
| `FlattenOp` | `flatten` | `[array]` — one level of flattening |

### String
| Op | Tag | Description |
|----|-----|-------------|
| `CatOp` | `cat` | Concatenate (primitives only, collections error) |
| `SubStrOp` | `substr` | `[string, start, length?]` — negative indices supported |
| `LowerOp` | `lower` | Lowercase |
| `UpperOp` | `upper` | Uppercase |
| `JoinOp` | `join` | `[array, separator]` — join array to string |
| `SplitOp` | `split` | `[string, separator]` — split string to array |
| `TrimOp` | `trim` | Trim whitespace |
| `StartsWithOp` | `startsWith` | `[string, prefix]` |
| `EndsWithOp` | `endsWith` | `[string, suffix]` |

### Object/Map
| Op | Tag | Description |
|----|-----|-------------|
| `MapValuesOp` | `values` | `[map]` → array of values |
| `MapKeysOp` | `keys` | `[map]` → array of string keys |
| `GetOp` | `get` | `[map, key]` → value (errors if missing) |
| `HasOp` | `has` | `[map, key]` → boolean |
| `EntriesOp` | `entries` | `[map]` → array of [key, value] pairs |

### Utility
| Op | Tag | Description |
|----|-----|-------------|
| `LengthOp` | `length` | Array or string length |
| `ExistsOp` | `exists` | True if no NullValues in list |
| `MissingNoneOp` | `missing` | Returns array of missing variable names |
| `MissingSomeOp` | `missing_some` | `[min, keys]` — returns missing if < min present |
| `TypeOfOp` | `typeof` | Returns type tag string: "null", "bool", "int", "float", "string", "array", "map" |

## Evaluation Strategies

The JLVM offers two evaluation strategies:

### Recursive (default)
- Standard recursive descent
- Simple, readable implementation
- Stack depth bounded by expression nesting depth
- Used via `JsonLogicEvaluator.recursive[F]`

### Tail-Recursive
- Continuation-passing style with explicit stack frames
- Stack-safe for deeply nested expressions
- Uses `Monad[F].tailRecM` for trampolining
- Used via `JsonLogicEvaluator.tailRecursive[F]`

Both produce identical results. The tail-recursive strategy uses `Frame.Eval` and `Frame.ApplyValue` frames with `Continuation` objects to track partially evaluated operator arguments.

**If/Else is lazy in both strategies:** Only the matching branch is evaluated, never all branches eagerly.

## Numeric Semantics

Arithmetic follows these rules:

1. **Type promotion:** Values are promoted to numeric via `promoteToNumeric`:
   - `IntValue` → `IntResult(BigInt)`
   - `FloatValue` → `FloatResult(BigDecimal)`
   - `BoolValue(true)` → `IntResult(1)`, `BoolValue(false)` → `IntResult(0)`
   - `NullValue` → `IntResult(0)`
   - `StrValue("")` → `IntResult(0)`
   - `StrValue("42")` → `IntResult(42)` (tries BigInt first, then BigDecimal)
   - `ArrayValue([x])` → recursively promote `x`
   - `ArrayValue([])` → `IntResult(0)`

2. **Int preservation:** If both operands are `IntResult` and the result is a whole number, the result stays `IntValue`. Otherwise it becomes `FloatValue`.

3. **Arbitrary precision:** Uses `BigInt` and `BigDecimal` — no overflow.

4. **Division:** Integer division truncates (`BigInt./`). Float division preserves decimals.

5. **Power:** Exponent magnitude capped at 999 to prevent resource exhaustion.

## Coercion Rules

The `==` operator uses **loose equality** with type coercion (similar to JavaScript's `==`):

1. Both values are coerced to `CoercedValue` primitives
2. Comparison follows these rules:
   - `null == null` → true
   - `null == anything_else` → false
   - `bool == bool` → direct comparison
   - `bool == int` → `true == 1`, `false == 0`
   - `int == int` → direct comparison
   - `int == string` → parse string as BigInt, compare
   - `float == string` → parse string as BigDecimal, compare
   - `string == string` → direct comparison

The `===` operator uses **strict equality** — values must be the same type AND equal. No coercion.

**Array/Map coercion to primitive:** Single-element arrays/maps coerce to their element. Empty arrays/maps coerce to `IntResult(0)`. Multi-element collections cannot be coerced (throws `JsonLogicException`).

## Callback Operators (Higher-Order)

Callback operators (`map`, `filter`, `reduce`, `all`, `some`, `none`, `find`, `count`) take an array and a predicate/transformer expression. The callback expression is wrapped in a `FunctionValue` and passed to the semantics layer.

During callback evaluation, each array element becomes the **extra context** for that iteration. Within the callback, `{"var": ""}` returns the current element.

**Reduce** is special: the callback receives a context with `{"current": element, "accumulator": acc}`.

Example:
```json
{
  "filter": [
    {"var": "state.items"},
    {">": [{"var": "price"}, 100]}
  ]
}
```

For each element in `state.items`, the filter predicate is evaluated with that element as context. `{"var": "price"}` resolves to `element.price`.

## Key Differences from Standard JSON Logic

1. **Extended operators:** Metakit adds `abs`, `round`, `floor`, `ceil`, `pow`, `lower`, `upper`, `join`, `split`, `trim`, `startsWith`, `endsWith`, `unique`, `slice`, `reverse`, `flatten`, `values`, `keys`, `get`, `has`, `entries`, `typeof`, `default`, `exists`, `intersect`, `count`, `find`

2. **Arbitrary precision:** Uses `BigInt`/`BigDecimal` instead of JavaScript's `Number`. No floating-point precision issues.

3. **Map merging:** `merge` works on both arrays (concatenation) AND maps (key-value merge with later keys winning).

4. **Array-syntax operators:** `["+", 1, 2]` is equivalent to `{"+": [1, 2]}`.

5. **Computed var paths:** `{"var": {"+": [...]}}` evaluates the expression first, uses the result as the variable path.

6. **FunctionValue:** Callback expressions are first-class values, enabling the higher-order operators.

7. **Three-way comparisons:** `<` and `<=` accept 3 arguments for range checks: `{"<": [0, {"var": "x"}, 100]}` means `0 < x < 100`.

## Gotchas for AI Agent Generation

When generating JSON Logic for OttoChain state machines, watch for these:

### 1. Multi-key objects are MapExpressions, not operators
```json
// This is a MapExpression (effect producing new state), NOT an operator call:
{
  "balance": {"+": [{"var": "state.balance"}, 100]},
  "name": {"var": "state.name"}
}

// This is an operator call:
{"+": [{"var": "state.balance"}, 100]}
```

The distinction: single-key objects where the key is a known operator → `ApplyExpression`. Multi-key objects OR single-key with unknown key → `MapExpression`.

### 2. Numbers: 1 vs 1.0
```json
1    →  IntValue(1)
1.0  →  FloatValue(1.0)
```
Use integers when you mean integers. `===` comparison between `IntValue(1)` and `FloatValue(1.0)` returns **false**.

### 3. Guard truthiness
Guards must evaluate to a **truthy** value. An empty array `[]`, empty string `""`, `0`, `false`, or `null` are all falsy — even if your intent was "no condition needed". Use `{"==": [1, 1]}` for an always-true guard.

### 4. Effect keys determine new state
Only the keys you include in an effect's result become the new state. If you want to preserve `state.name`, you must explicitly include `"name": {"var": "state.name"}` in the effect. **Missing keys are not carried forward.**

### 5. Reserved keys in effects
Keys prefixed with `_` are extracted by OttoChain's engine as side effects and NOT included in the new state:
- `_oracleCall` → invokes an oracle
- `_triggers` → fires cross-machine events
- `_spawn` → creates child fibers
- `_emit` → emits observable events

### 6. Merge for arrays, MapExpression for objects
To combine objects, use a `MapExpression` (multi-key object). `merge` on maps works but is an operator call — it can't be the top-level effect structure.

### 7. Strict vs loose equality
Use `===` (strict) for type-safe comparisons. `==` (loose) coerces types, which can lead to surprises: `{"==": [0, ""]}` is `true` because empty string coerces to `0`.

### 8. The `var` operator never resolves implicitly
Bare strings are always literal values:
```json
{"==": ["state.x", 5]}     // Compares the STRING "state.x" to 5
{"==": [{"var": "state.x"}, 5]}  // Compares the VALUE of state.x to 5
```

### 9. Callback context scope
Inside `map`/`filter`/`reduce` callbacks, `{"var": ""}` refers to the current element, NOT the root context. To access root-level variables from within a callback, use the full path.

### 10. Division is integer when both operands are integers
`{"/": [7, 2]}` returns `IntValue(3)` (truncated), not `FloatValue(3.5)`. Use `FloatValue` operands if you need decimal results: `{"/": [7, 2.0]}` returns `FloatValue(3.5)`.
