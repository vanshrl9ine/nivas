# 3. Stateless access tokens, stateful refresh tokens

**Status:** accepted

## Context

The API must scale horizontally with no sticky sessions, and users must be able to
sign out of all devices — for example after losing a phone.

Those two requirements pull in opposite directions. A self-contained token needs
no lookup, which is what makes it scalable, and is for the same reason impossible
to revoke before it expires.

## Decision

Split the difference by token type.

**Access tokens are stateless and short-lived (15 minutes).** Signed JWTs carrying
the user id, email and role. No store lookup on any authenticated request. The
worst case for a leaked one is bounded by its lifetime.

**Refresh tokens are stateful and long-lived (30 days).** Each carries a `jti`
tracked in Redis under `refresh:<jti>`, plus a per-user index at
`refresh:user:<id>` holding that user's live jtis. Revocation is a `DEL`.

Three details that are not decoration:

- **Rotation.** Refreshing revokes the old token and issues a new one. A stolen
  refresh token is only useful until the legitimate client next refreshes.
- **A type claim.** Every token carries `typ`, checked on parse. Without it a
  refresh token is a perfectly valid access token, and the 15-minute access
  lifetime becomes a fiction.
- **Constant-time login.** The password encoder runs even when the email does not
  exist, against a fixed placeholder hash. Skipping it makes login timing a user
  enumeration oracle.

Roles stay coarse (`SEEKER`, `OWNER`, `AGENT`, `ADMIN`). "May this user edit *this*
listing?" is an ownership check in the service layer, not an authority — encoding
it in the token would mean reissuing tokens whenever ownership changes.

## Consequences

**What this buys.** Authenticated requests cost no I/O to authorise. Any node can
serve any request. Sign-out-everywhere works. Redis TTLs expire refresh tokens
automatically, so there is no cleanup job and no growing table.

**What it costs.**

- A revoked *user* — disabled or deleted — keeps a working access token for up to
  15 minutes. Accepted: refresh is blocked immediately, so the blast radius is one
  token lifetime. Making this instant would require a lookup per request, which
  defeats the whole design.
- Redis becomes a dependency of the login path. If it is down, refresh fails and
  users get logged out as their access tokens expire. Existing access tokens keep
  working, so a brief blip degrades rather than breaks.
- The signing secret is a single shared value. Real deployments should move to
  asymmetric keys so the verifying service never holds signing material.
- BCrypt strength 12 rather than the default 10 — roughly 4x the work per hash,
  still comfortably under 250ms, and paid only on login.

## Alternatives considered

**Server-side sessions.** Trivially revocable and simpler to reason about. Rejected
because it puts a store lookup on every request and pushes towards sticky sessions.

**Stateless refresh tokens.** Removes Redis from the auth path entirely, and makes
"sign out everywhere" impossible. Not an acceptable feature to drop.

**An external identity provider.** The right answer for a real product, and how
this would be built at a company. Deliberately hand-rolled here because the
mechanics — rotation, type confusion, timing — are the interesting part.
