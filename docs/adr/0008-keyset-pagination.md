# 0008. Keyset (cursor) pagination, not offset pagination

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

The Reconciliation Command Center grid is the heaviest read in the system: a filtered, sorted,
paginated view over a projection that may hold millions of rows and is being written to
continuously by the projection consumer.

`OFFSET`-based pagination is the default in most frameworks and is wrong for both reasons.

## Decision drivers

- Page 5,000 must cost the same as page 1.
- An operator paging through a live queue must not silently skip rows.
- Export of the current filtered query must stream without holding the whole result in memory.

## Considered options

1. **Offset/limit pagination** — `LIMIT 50 OFFSET 250000`.
2. **Keyset (cursor) pagination** (chosen).
3. **Search-after via a dedicated search engine.**

## Decision outcome

**Chosen: option 2 — keyset pagination.**

The cursor encodes the sort key of the last row on the previous page. The next page is:

```sql
WHERE (sort_key, id) < (:last_sort_key, :last_id)
ORDER BY sort_key DESC, id DESC
LIMIT :size
```

Two details that are easy to get wrong and are therefore explicit here:

1. **The tiebreaker is mandatory.** `sort_key` alone is not unique — many cases share a timestamp.
   Without the `id` tiebreaker, rows at a page boundary are skipped or repeated. The composite
   `(sort_key, id)` comparison is what makes the cursor total.
2. **The cursor is opaque to the client** (base64 of the key tuple) so its encoding can change
   without breaking clients, and clients cannot fabricate one to probe arbitrary ranges.

The sort key must be covered by an index that matches the sort direction, or the query degenerates
to a full scan plus in-memory sort and the entire benefit is lost.

### The two problems this solves

**Performance.** `OFFSET 250000` requires the database to fetch and discard 250,000 rows before
returning 50. Cost grows linearly with page depth. Keyset pagination seeks directly into the index
and reads 50 rows — **constant cost regardless of depth**.

**Correctness under concurrent writes.** This is the more important one and the reason it is not
merely an optimisation.

With offset pagination on a live table: an operator reads page 1 (rows 1–50). While they read,
the projection consumer inserts 10 new rows that sort above the current page. The operator
requests page 2 (`OFFSET 50`) — but the window has shifted by 10, so **rows 41–50 are served
again and rows they have not seen are pushed past the boundary**. In an exception queue, that
means an analyst can page through the entire backlog and never see a break that was there the
whole time.

Keyset pagination anchors on the last row actually seen, so newly inserted rows do not shift the
window. The operator sees a consistent forward traversal.

### Consequences

**Positive**

- Constant-time page fetches at any depth.
- No skipped or duplicated rows under concurrent inserts.
- Server-side CSV export of the current filtered query can stream by walking the cursor, with
  bounded memory.

**Negative**

- **No random page access.** There is no "jump to page 47" and no total page count without a
  separate (expensive) count query. The UI must be designed around infinite scroll / next-previous
  rather than numbered pages. This is a real product constraint driven by a technical decision,
  and the console is designed accordingly.
- Backward pagination requires reversing the comparison and the sort, then re-reversing the
  results — easy to get subtly wrong, and therefore explicitly tested.
- Every sortable column needs a supporting index with the right direction. Adding a new sort
  option is an index change, not just a UI change.
- Changing sort order invalidates existing cursors; the API must reject a cursor issued under a
  different sort rather than returning nonsense.

## Pros and cons of the options

### Option 1 — offset pagination

- Good: trivial to implement; supports random page access and total counts; every framework does
  it by default.
- Bad: linear degradation with depth.
- Bad: **incorrect under concurrent writes** — rows are skipped, which for an exception queue is a
  correctness bug, not a UX annoyance.

### Option 2 — keyset (chosen)

- Good: constant cost, stable traversal.
- Bad: no random access, no cheap total count, index discipline required.

### Option 3 — search-after in Elasticsearch

- Good: same stable-traversal property, plus rich full-text search.
- Bad: rejected with Elasticsearch itself (ADR-0002) — another container and another consistency
  model for a search capability this system does not need.

## More information

- Grid API and filter set: `docs/architecture.md`
- Rejected infrastructure: `docs/architecture.md` (rejected patterns)
