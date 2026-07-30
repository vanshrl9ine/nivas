# 2. Transactional outbox instead of dual writes

**Status:** accepted

## Context

When a listing is published it must appear in search. The obvious implementation
is to save to Postgres and then index into Elasticsearch in the same service
method.

That is a dual write, and it has no correct failure mode. If the Elasticsearch
call throws, the transaction has usually already committed — so the listing
exists but is unfindable, and nothing in the system knows. Wrapping the index
call inside the transaction is worse: Elasticsearch is not transactional, so a
rollback leaves a document behind for a listing that does not exist. Retrying
inline just moves the window.

The failure is also silent, which is what makes it expensive. Nobody files a bug
saying "my listing is missing from search" until days later.

## Decision

Every listing change writes a row to `outbox_events` **in the same transaction as
the change itself**. Because the event and the data commit or roll back together,
there is no state in which one exists without the other.

A relay then polls pending rows and publishes them to Kafka:

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` is what makes the relay safe to run on every application node —
each poller locks a disjoint set of rows and the others step over them. Without
it, N instances serialise behind one another on the same head rows.

The indexer consumes the event and **re-reads current state from Postgres** rather
than trusting a payload serialised at write time. It writes to Elasticsearch with
an external version taken from the row's optimistic-lock version, so a
late-arriving older event is rejected by the index rather than applied.

## Consequences

**What this buys.** Search cannot silently diverge from the database. Delivery is
at-least-once and every consumer is idempotent, so redelivery is harmless. The
index is fully derived and can be rebuilt from Postgres at any time —
`POST /api/v1/admin/index/rebuild` does exactly that. `nivas.outbox.backlog` is a
single number that goes up the moment the pipeline breaks.

**What it costs.**

- Search is *eventually* consistent. A listing is typically searchable within a
  second or two of publishing, not instantly. For a property portal that is
  invisible; for something like a seat booking it would not be acceptable.
- The extra read in the indexer is real. It is the price of not having to reason
  about event ordering, which we judged the better trade.
- The relay holds row locks while publishing, so a Kafka stall blocks that batch.
  Batches are kept small and the send has a hard timeout.
- `outbox_events` grows and needs pruning. A scheduled job drops published rows
  after seven days; the hot query uses a partial index so the backlog scan stays
  proportional to pending work rather than to the full log.

## Alternatives considered

**Change data capture (Debezium reading the WAL).** Strictly better decoupling —
the application does not know it is being replicated at all. Rejected because it
adds Kafka Connect and a WAL slot to operate, and a stuck slot can fill the
Postgres disk. Not a trade worth making at this size, but the migration path is
clean if throughput ever demands it.

**Synchronous indexing with a compensating retry queue.** Simpler on the happy
path, but the retry queue is just an outbox with fewer guarantees and no ordering.

**Rebuild-only, on a schedule.** Cheap, but a listing published at 09:01 would not
appear until the next rebuild. Fine as the safety net it now is; not a primary
mechanism.
