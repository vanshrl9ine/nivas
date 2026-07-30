# 5. Search relevance and the index mapping

**Status:** accepted

## Context

"2 bhk koramangala under 50k with parking" has to work. So does panning a map,
ticking three amenity boxes, and sorting by distance — with facet counts showing
how many results each further refinement would leave.

## Decision

### Filters never touch the score

Only free text goes in the `must` clause. Every structured constraint — price,
bedrooms, city, locality, amenities, geo — goes in `filter`.

Two reasons. Filters are not scored, so an exact price match does not become "more
relevant", which is meaningless. And filters are cacheable in Elasticsearch's node
query cache, which `must` clauses are not.

### Text matching

`best_fields` over `title^4`, `localityName.text^3`, `city.text^2`,
`addressLine^1.5`, `description`.

`best_fields` rather than `cross_fields`: a listing whose *title* matches "2 bhk
indiranagar" is a better result than one matching "2 bhk" in the title and
"indiranagar" only in a rambling description. The locality boost is high because
in this product a place name is a location intent far more often than it is a word
someone wants found inside prose.

Fuzziness is `AUTO` with `prefix_length: 2` — short tokens match exactly, longer
and more misspellable words get an edit or two of slack, and the prefix constraint
keeps it from matching everything.

### Boosts are multiplicative and capped

Applied via `function_score` only when there is query text — with no text every
document ties, so the functions would cost time for no ordering benefit.

- verified listings, weight 1.35 — the product's quality signal
- a Gaussian freshness decay over 30 days with a 3-day offset — a year-old listing
  is usually already gone, but today's and Tuesday's should not be separated
- popularity via `log1p(viewCount)`, heavily damped — undamped, one listing with
  100k views buries everything else, and the counter is only approximate anyway
- listings with 3+ photos, weight 1.15 — listings without photos barely convert

`boost_mode: multiply` and `max_boost: 3.0` so these *adjust* text relevance rather
than replace it. A poor text match must not float to the top merely because it is
verified and recent.

### The mapping is hand-written and strict

Authored as JSON rather than derived from annotations, because multi-fields,
edge-ngram autocomplete, synonym graphs and completion contexts have no faithful
annotation equivalent — and search quality lives in exactly those details.

`dynamic: strict`, so an unmapped field fails the write instead of being silently
indexed with a guessed type. This is why the document must not carry Spring Data's
default `_class` type hint (`writeTypeHint = FALSE`), and why a unit test reflects
over `PropertyDocument` and asserts every field appears in the mapping.

Synonyms are **search-time only**. Index-time synonyms would require a full reindex
to change, and the synonym list is the thing most likely to be tuned.

Autocomplete uses `nivas_autocomplete_index` for indexing and
`nivas_autocomplete_search` for querying. Using the ngram analyzer at search time
too would split the query into ngrams as well, matching almost everything.

### Always a tiebreak

Every sort ends with `id ASC`. Without it two listings with an identical sort value
can swap places between requests, so a user paging through results sees one twice
and another never.

## Consequences

**What this buys.** One round trip returns results, facet counts and price stats.
Relevance is tunable in config-shaped places rather than in code. Query
construction is unit-testable by serialising to JSON, with no cluster involved.

**What it costs.**

- Boost weights are judgement, not measurement. They should be replaced by
  click-through data as soon as there is any. They are stated as constants in one
  class specifically so that replacing them is a small change.
- `dynamic: strict` means adding a document field without updating the mapping
  breaks indexing. That is the intent, and the test catches it at build time.
- Facets are computed over the filtered result set, so each reflects the other
  filters already applied. That is what users expect from a refinement panel, but
  it does mean a facet count is not a global count.
- Deep pagination uses `from`/`size` and degrades past a few thousand results.
  `search_after` is the fix if it ever matters; for a product where nobody visits
  page 400, it is not worth the statefulness.

## Alternatives considered

**Annotation-derived mapping.** Less to maintain, and cannot express the analyzers
that make search good. Rejected.

**A learning-to-rank plugin.** The right destination with real traffic. Pointless
without click data.

**Postgres `tsvector` + PostGIS.** One less system. Rejected on faceting — every
facet becomes its own aggregate query.
