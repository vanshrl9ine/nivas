# Architecture

Postgres is the system of record. Elasticsearch serves search and is derived
from it — always rebuildable, never authoritative. Redis carries the state that
must be shared across instances but does not belong in a relational store.

## System overview

```mermaid
flowchart TB
    subgraph client[" "]
        WEB["Next.js 16<br/><i>nivas-web</i>"]
    end

    subgraph api["Spring Boot 4 · nivas-api"]
        direction TB
        SEC["Security<br/><small>JWT filter chain</small>"]
        CAT["catalog<br/><small>Property aggregate</small>"]
        SRCH["search<br/><small>query builder, facets</small>"]
        LEAD["lead<br/><small>enquiries</small>"]
        SAVED["savedsearch<br/><small>alerts</small>"]
        OUT["outbox<br/><small>relay</small>"]
        IDX["indexing<br/><small>projector</small>"]
    end

    PG[("Postgres 17<br/><b>system of record</b>")]
    RD[("Redis 7<br/>cache · limits · counters")]
    ES[("Elasticsearch 9<br/><b>derived index</b>")]
    KFK{{"Kafka<br/>nivas.property.changed"}}

    WEB -->|"REST + Bearer"| SEC
    SEC --> CAT & SRCH & LEAD & SAVED

    CAT -->|"write + outbox row<br/><b>one transaction</b>"| PG
    LEAD --> PG
    SAVED --> PG

    OUT -->|"poll FOR UPDATE<br/>SKIP LOCKED"| PG
    OUT -->|publish| KFK
    KFK -->|consume| IDX
    IDX -->|"re-read current state"| PG
    IDX -->|"upsert, external version"| ES

    SRCH -->|query| ES
    SAVED -->|"count new matches"| ES

    SEC -.->|"refresh tokens"| RD
    CAT -.->|"cache-aside"| RD
    SRCH -.->|"rate limit"| RD

    ADMIN["POST /admin/index/rebuild"] -.->|"rebuild from truth"| IDX

    classDef store fill:#e8f0fe,stroke:#4285f4,color:#1a1a1a
    classDef derived fill:#fef7e0,stroke:#f9ab00,color:#1a1a1a
    class PG,RD store
    class ES derived
```

The dotted line from the admin endpoint is the safety net: because the index is
derived, it can be dropped and rebuilt from Postgres at any time. That is what
makes the whole pipeline recoverable rather than merely careful.

## The write path

Publishing a listing is the case everything else is designed around. The listing
and its event must commit together, or search silently diverges from the truth.

```mermaid
sequenceDiagram
    autonumber
    actor Owner
    participant API as PropertyService
    participant PG as Postgres
    participant Relay as OutboxRelay
    participant K as Kafka
    participant IDX as PropertyIndexer
    participant ES as Elasticsearch

    Owner->>API: PATCH /properties/{id}/status ACTIVE

    rect rgb(232, 240, 254)
        Note over API,PG: ONE transaction — this is the whole point
        API->>PG: UPDATE properties SET status='ACTIVE'
        API->>PG: INSERT INTO outbox_events
    end
    API-->>Owner: 200 (search not updated yet)

    loop every second
        Relay->>PG: SELECT … FOR UPDATE SKIP LOCKED
        Note right of Relay: SKIP LOCKED lets every<br/>instance poll concurrently
        Relay->>K: publish (key = listing id)
        Relay->>PG: mark PUBLISHED
    end

    K->>IDX: PropertyChangedEvent
    IDX->>PG: re-read current state
    Note right of IDX: Re-reading, not trusting the<br/>payload, is what makes<br/>redelivery harmless
    IDX->>ES: index, version = row version
    Note right of ES: External versioning rejects<br/>a late older event
    IDX->>K: acknowledge
```

If the process dies between steps 3 and 4, the event is still in the outbox and
is retried. If it dies after publishing but before acknowledging, the event is
redelivered — and steps 8–9 make that a no-op rather than a corruption.

The cost is that search is *eventually* consistent: a listing is typically
searchable a second or two after publishing. For a property portal that is
invisible. For seat booking it would be unacceptable, and this would be the
wrong design.

## The read path

```mermaid
sequenceDiagram
    autonumber
    actor Visitor
    participant API as PropertyController
    participant RD as Redis
    participant PG as Postgres

    Visitor->>API: GET /properties/{id}

    API->>RD: GET nivas:cache:property-detail::{id}
    alt cached
        RD-->>API: payload
    else miss
        API->>PG: SELECT … (single query, entity graph)
        PG-->>API: listing
        API->>RD: SETEX 10m
    end

    API->>RD: SETNX seen:{id}:{viewer} 30m
    alt first view in the window
        API->>RD: HINCRBY pending views
        API->>RD: ZINCRBY trending:{today}
    end
    API-->>Visitor: 200

    Note over RD,PG: every 30s
    RD->>PG: RENAME + batched UPDATE … view_count + delta
```

Counting a view as a relational write would put a row lock on the most popular
listings and turn the busiest read in the product into a write on the primary.
Accumulating in Redis and folding down in batches makes the counter approximate
— fine for a ranking signal, wrong for anything billed.

`RENAME` before reading matters: read-then-delete would drop every view recorded
between the two calls. The snapshot is only discarded once the counts are
durably in Postgres, and folded back on failure.

## Where each store earns its place

| Store | Holds | Why not elsewhere |
| --- | --- | --- |
| **Postgres** | Listings, users, leads, saved searches, outbox | Transactions, foreign keys, unique constraints. Lead idempotency and ownership would be application-level guesses anywhere else. |
| **Elasticsearch** | One flat document per ACTIVE listing | Faceted aggregations returned with every query, geo filters, analyzers. Postgres makes you hand-roll each facet as its own slow query. |
| **Redis** | Refresh tokens, cached payloads, rate-limit windows, view counters | Keyed lookups with natural expiry. `SETEX` gives cleanup for free; Postgres would need a table and a job. |
| **Kafka** | The change stream | Decouples the relay from the indexer and preserves per-listing ordering via partition key. |

## What is deliberately not here

- **No CQRS framework, no event sourcing.** The outbox gives the one guarantee
  that was actually needed. Event sourcing would mean rebuilding state from a
  log, which nothing here requires.
- **No microservices.** A modular monolith with clear package boundaries. The
  indexer could be extracted into its own deployable without touching its code —
  it already communicates only over Kafka — and that is the point.
- **No service mesh, no Kubernetes manifests.** They would demonstrate YAML, not
  engineering.
