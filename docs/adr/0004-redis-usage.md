# 4. Redis for caching, rate limiting and view counting

**Status:** accepted

## Context

Three separate problems, all with the same shape: shared state that must be
correct across every application instance and does not belong in Postgres.

## Decision

### Cache-aside on listing detail, with explicit invalidation

The detail endpoint is the most-requested in the product and changes only when the
owner edits. Cached under `property-detail`, evicted explicitly on every write
path rather than left to the TTL.

Per-cache TTLs, not one global value — locality reference data is safe for hours,
search results are stale within seconds. Nulls are never cached: caching a 404
would pin it for the whole TTL for a listing that is about to be published.

The cache manager is transaction-aware, so eviction happens after commit rather
than before. Evicting early opens a window where a concurrent read repopulates the
cache from the pre-commit state.

Values are stored as JSON rather than JDK serialisation: entries stay readable from
`redis-cli` while debugging, and a class whose `serialVersionUID` shifts between
deploys does not poison the cache. Polymorphic type information is restricted to
our own packages, so a tampered entry cannot name an arbitrary class as a
deserialisation gadget.

### Sliding-window rate limiting, in Lua

One sorted set per subject per endpoint; each request is a member scored by
timestamp; anything outside the window is dropped before counting.

It is a **single Lua script** because check-then-increment across two round trips
is a race. Under concurrency that race lets callers exceed the limit — which is
precisely when a rate limiter is supposed to work. A script executes atomically on
the server, so the decision and the record of it cannot be interleaved.

A sliding window rather than a fixed one because a fixed window permits a burst of
2x the limit across a boundary.

The limiter **fails open**. If Redis is unreachable the request is allowed and a
warning logged: a rate limiter that takes the whole API down when Redis hiccups is
a worse outage than the abuse it was defending against.

### View counting in Redis, flushed in batches

A detail-page view is the highest-frequency write in the product. `UPDATE ... SET
view_count = view_count + 1` per view puts a row lock on the most popular listings
and turns reads into writes on the primary.

Views instead accumulate in a Redis hash and a scheduled job folds them into
Postgres as relative SQL increments — so concurrent flushes compose rather than
clobber. A per-viewer key with a 30-minute TTL de-duplicates, so a refresh loop
cannot inflate a listing's rank.

Draining uses `RENAME` then read, not read then delete, which would drop every view
recorded between the two calls. The snapshot is only deleted once the counts are
durably in Postgres; on failure it is folded back for the next run.

## Consequences

**What this buys.** The hot read path mostly avoids Postgres. Rate limits are a
property of the system rather than of whichever node the load balancer picked.
View counting costs no database contention.

**What it costs.**

- View counts are eventually consistent and approximate. Fine for a number used to
  rank and display; it would not be acceptable for anything billed.
- Anonymous rate limiting keys on IP, which is unfair behind shared NAT. The
  alternative — one global bucket — would let a single client throttle everyone.
- `X-Forwarded-For` is trusted. If the API is ever exposed without a proxy in
  front, that header becomes client-controlled and the limit trivially bypassable.
  The proxy must always overwrite rather than append it.
- Redis is now on the critical path for login refresh. Caching and rate limiting
  both degrade gracefully; refresh does not.

## Alternatives considered

**In-memory caching (Caffeine).** Faster and one less dependency, but each instance
gets its own view, so an eviction on one node leaves the others serving stale data.

**A token bucket via `INCR` + `EXPIRE`.** Cheaper and simpler. Rejected for the
fixed-window burst problem, and because two commands are not atomic together.

**Postgres advisory locks for rate limiting.** Would work, and puts load on exactly
the system this design is trying to keep quiet.
