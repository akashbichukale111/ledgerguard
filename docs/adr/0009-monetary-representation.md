# 0009. Monetary representation: BigDecimal + currency, scale from minor units

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

This is the first thing a FinTech reviewer checks, and the fastest way to lose credibility. A
system that stores money in a `double` is not a financial system, regardless of what else it does
well.

The classic demonstration:

```java
0.1 + 0.2 == 0.30000000000000004   // binary floating point cannot represent 0.1
```

Applied across millions of postings, this produces reconciliation breaks caused entirely by the
representation — the system generates the very problem it exists to detect.

## Decision drivers

- Monetary values must be exact, always.
- Currency must be inseparable from amount; a bare number is meaningless and dangerous.
- Rounding must be explicit at every site, never implicit.
- The JSON boundary to a JavaScript client is a known destroyer of precision.

## Considered options

1. **`double` / `float`** — rejected outright.
2. **Long minor units** (amount in cents as `long`).
3. **`BigDecimal` + ISO 4217 currency in a `Money` value type** (chosen).
4. **A third-party money library** (JSR-354 / Moneta, Joda-Money).

## Decision outcome

**Chosen: option 3.** A `Money` value type: immutable, final, `BigDecimal amount` +
`CurrencyCode currency`.

### Rules, each of which is enforced in code and tested

**Scale derives from the currency's minor units.** JPY 0, USD/EUR 2, BHD/KWD 3. Scale is enforced
on construction. An amount with excess precision is **rejected**, not silently truncated — silent
truncation is how money disappears.

**Arithmetic across different currencies throws.** There is no implicit conversion. `USD 10 +
EUR 5` is a programming error, not a value. FX conversion is **out of scope for v1** and documented
as such; adding it means introducing a rate source, a rate timestamp, and a conversion audit trail,
none of which exist here.

**Equality uses `compareTo` semantics for the amount.** `BigDecimal.equals` compares scale as well
as value, so `2.50` and `2.5` are *unequal* under `equals` — a notorious source of bugs. `Money`
equality must not inherit that surprise.

**Rounding is `HALF_EVEN` (banker's rounding) for derived values.** `HALF_UP` introduces a
systematic upward bias: over many roundings, the excess accumulates in one direction. `HALF_EVEN`
rounds to the nearest even digit on ties, so the bias cancels over a large population. This is the
standard for financial aggregates and the reason it is not `HALF_UP`.

**Rounding never applies to stored transaction amounts** — only to derived aggregates. A stored
amount is a fact; rounding a fact is data corruption. Every rounding site is explicit; there is no
default rounding anywhere.

**Persistence: `NUMERIC(19,4)` plus a `CHAR(3)` currency column.** Never one column, never a
floating-point column. Storing `"USD 10.50"` in one text column makes arithmetic and indexing
impossible; storing the amount without the currency makes the value meaningless.

**JSON serialises amounts as strings, not numbers.** `JSON.parse` in JavaScript produces an
IEEE-754 double, so a JSON *number* is destroyed at the browser boundary regardless of how
carefully the backend handled it. The frontend uses a decimal-safe library for display and
**never performs arithmetic on money** — all monetary computation happens server-side.

**Tolerance comparisons are explicit**, in absolute (`Money`) and relative (basis points,
`BigDecimal`) forms. Never `==`, never `equals` on `BigDecimal`.

**A CI check greps for `double`/`float`/`Double`/`Float` in monetary contexts and fails the
build**, backed by an ArchUnit rule banning them from domain packages outright.

### Consequences

**Positive**

- Exact arithmetic; no representation-induced breaks.
- Currency mismatches fail loudly at the point of the error rather than producing a plausible
  wrong number.
- The invariant is enforced by the type system, so it cannot be forgotten at a call site.

**Negative**

- `BigDecimal` is slower and allocates more than primitives. Irrelevant at this throughput and
  the wrong thing to optimise.
- `BigDecimal` is verbose and its API has sharp edges (`equals` vs `compareTo`, mandatory
  `RoundingMode` on `divide`). Encapsulating it in `Money` is precisely what limits the blast
  radius of those edges to one well-tested class.
- Scale-on-construction means callers must produce correctly-scaled input, so parsing and
  validation carry more responsibility.

## Pros and cons of the options

### Option 1 — `double`/`float`

- Bad: cannot exactly represent most decimal fractions. Disqualifying. Listed so the rejection is
  on the record.

### Option 2 — long minor units

- Good: exact, fast, compact; a legitimate and widely used choice (Stripe's API does this).
- Bad: scale must be tracked out-of-band — `1050` is meaningless without knowing it is USD cents,
  and the JPY/BHD cases (0 and 3 minor units) make a fixed assumption wrong.
- Bad: overflow is a real concern for large notionals.
- Bad: division and percentage calculations (basis-point tolerances) become manual scaling
  exercises, which is where bugs live.

### Option 3 — `BigDecimal` + currency (chosen)

- Good: exact, self-describing, explicit about rounding.
- Bad: verbose, slower — both acceptable.

### Option 4 — JSR-354 (Moneta) or Joda-Money

- Good: well-tested, handles currency metadata and formatting.
- Bad: JSR-354 is a large API surface for what is ultimately `BigDecimal` + currency + rules, and
  it drags in a dependency whose behaviour must still be understood in detail to be trusted with
  money.
- Bad: the specific rules that matter here (scale-on-construction rejection, cross-currency throw,
  no-rounding-on-stored-amounts) are policy decisions this system needs to make and demonstrate
  explicitly, not inherit.
- **Verdict: a reasonable production choice; rejected here because `Money` is small enough to own
  and its rules are part of what this repository is demonstrating.**

## More information

- Type definition and edge cases: `docs/domain-model.md`
- Tolerance matching: `docs/reconciliation-engine.md`
- Test coverage including boundary values: `docs/testing.md`
