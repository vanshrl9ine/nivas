# Architecture decision records

One file per decision that would be expensive to reverse, or that a reader would
otherwise reasonably assume was an oversight.

Each records the context, the decision, and — the part that usually gets left out
— **what it costs** and what was rejected. A decision without a stated downside is
usually a decision nobody actually made.

| # | Decision |
| --- | --- |
| [0001](0001-postgres-as-source-of-truth.md) | Postgres as the system of record, Elasticsearch as a derived index |
| [0002](0002-transactional-outbox.md) | Transactional outbox instead of dual writes |
| [0003](0003-jwt-and-refresh-tokens.md) | Stateless access tokens, stateful refresh tokens |
| [0004](0004-redis-usage.md) | Redis for caching, rate limiting and view counting |
| [0005](0005-search-relevance.md) | Search relevance and the index mapping |
