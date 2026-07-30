# 1. Postgres as the system of record, Elasticsearch as a derived index

**Status:** accepted

## Context

The product needs relational integrity (a listing belongs to an owner, a lead
belongs to a listing, money must not be a float) *and* it needs full-text,
faceted, geo-aware search. No single store is good at both.

## Decision

Postgres owns the truth. Elasticsearch owns query performance and is treated as a
cache that happens to be very good at searching — derived, disposable, and
rebuildable from Postgres at any time.

Concretely:

- Every write goes to Postgres first, inside a transaction.
- Nothing reads from Elasticsearch to make a write decision.
- Only `ACTIVE` listings are indexed; the write side enforces this in one place.
- The index can be dropped and rebuilt without data loss.

Postgres-side choices that follow from taking it seriously as the record:

- Money is `numeric(14,2)`, never floating point.
- Enum-ish columns are `varchar` + `CHECK` rather than native PG enums — adding a
  value to a native enum cannot always run inside a transactional migration,
  while widening a `CHECK` is trivial.
- Emails are `citext`, so case-insensitive uniqueness is enforced by the database
  rather than by remembering to call `lower()` at each call site.
- Mutable aggregates carry a `version` column for optimistic locking. Two agents
  editing one listing would otherwise silently clobber each other — and the losing
  write would still emit an outbox event, pushing stale data into search.
- Constraints encode invariants that must hold regardless of application bugs:
  a listing cannot be `ACTIVE` without a `published_at`, a price must be positive,
  a floor cannot be above the building's total floors.

## Consequences

**What this buys.** Corruption has one place to originate and one place to fix.
Search can be re-derived, so an indexing bug is an inconvenience rather than data
loss. Schema changes go through Flyway and are reviewable.

**What it costs.**

- Data is denormalised into the index, so some facts exist twice. That
  duplication is deliberate: locality name, cover image and price-per-sqft are all
  derivable, but computing them at query time means either a join Elasticsearch
  cannot do or a script that runs per hit.
- Every searchable field change costs a reindex. The aggregate tracks whether a
  mutation is search-relevant so that editing a private note does not trigger one.
- `ddl-auto: validate`, never `update`. Hibernate checks that the schema matches
  and refuses to start otherwise; it is not permitted to alter anything.

## Alternatives considered

**Elasticsearch as the primary store.** Removes the sync problem entirely. Rejected:
no transactions, no foreign keys, no unique constraints. Lead idempotency and
listing ownership would become application-level guesses.

**Postgres full-text search only.** `tsvector` plus PostGIS would genuinely cover a
lot, and for a smaller catalogue it would be the right call — one less system to
run. Rejected because faceted aggregations across many dimensions, returned with
every query, are exactly what Elasticsearch is built for and what Postgres makes
you hand-roll with a slow query per facet.
