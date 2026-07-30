-- Nivas baseline schema.
--
-- Conventions used throughout:
--   * UUID v4 primary keys, generated DB-side so inserts stay idempotent-friendly.
--   * Enum-ish columns are varchar + CHECK rather than native PG enums: adding a
--     value to a native enum can't run inside a transactional migration on older
--     servers, and CHECK constraints are trivial to widen.
--   * Money is numeric(14,2). Never float.
--   * Every table carries created_at/updated_at; mutable aggregates also carry a
--     `version` column for JPA optimistic locking.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email          citext      NOT NULL,
    password_hash  text        NOT NULL,
    full_name      varchar(120) NOT NULL,
    phone          varchar(20),
    role           varchar(16) NOT NULL DEFAULT 'SEEKER',
    enabled        boolean     NOT NULL DEFAULT true,
    email_verified boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('SEEKER', 'OWNER', 'AGENT', 'ADMIN'))
);

-- ---------------------------------------------------------------------------
-- localities -- the geographic vocabulary the whole product is filtered by
-- ---------------------------------------------------------------------------

CREATE TABLE localities (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       varchar(160) NOT NULL,
    name       varchar(120) NOT NULL,
    city       varchar(80)  NOT NULL,
    state      varchar(80)  NOT NULL,
    -- Centroid, used to bias relevance and to centre a map when a user picks
    -- a locality without drawing their own bounding box.
    latitude   double precision,
    longitude  double precision,
    parent_id  uuid REFERENCES localities (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_localities_slug UNIQUE (slug),
    CONSTRAINT ck_localities_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_localities_lon CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE INDEX ix_localities_city ON localities (city);
CREATE INDEX ix_localities_parent ON localities (parent_id);
-- Trigram index powers "did you mean" locality lookups without hitting ES.
CREATE INDEX ix_localities_name_trgm ON localities USING gin (name gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- properties -- the write-side aggregate root
-- ---------------------------------------------------------------------------

CREATE TABLE properties (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    locality_id         uuid         REFERENCES localities (id) ON DELETE SET NULL,

    title               varchar(180) NOT NULL,
    description         text,
    listing_type        varchar(16)  NOT NULL,
    property_type       varchar(24)  NOT NULL,
    status              varchar(20)  NOT NULL DEFAULT 'DRAFT',

    bedrooms            smallint,
    bathrooms           smallint,
    balconies           smallint,
    furnishing          varchar(16),
    carpet_area_sqft    integer,
    built_up_area_sqft  integer,
    floor_number        smallint,
    total_floors        smallint,
    age_years           smallint,

    price               numeric(14, 2) NOT NULL,
    maintenance_monthly numeric(12, 2),
    security_deposit    numeric(14, 2),
    price_negotiable    boolean      NOT NULL DEFAULT false,
    available_from      date,

    address_line        varchar(255),
    city                varchar(80)  NOT NULL,
    state               varchar(80)  NOT NULL,
    pincode             varchar(12),
    latitude            double precision,
    longitude           double precision,

    rera_id             varchar(64),
    verified            boolean      NOT NULL DEFAULT false,
    verified_at         timestamptz,

    -- Denormalised counter, periodically flushed down from Redis. Approximate
    -- by design: it is a ranking signal, not an invoice.
    view_count          bigint       NOT NULL DEFAULT 0,

    published_at        timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0,

    CONSTRAINT ck_properties_listing_type CHECK (listing_type IN ('SALE', 'RENT')),
    CONSTRAINT ck_properties_type CHECK (property_type IN
        ('APARTMENT', 'INDEPENDENT_HOUSE', 'VILLA', 'PLOT', 'STUDIO', 'PENTHOUSE', 'OFFICE', 'SHOP')),
    CONSTRAINT ck_properties_status CHECK (status IN
        ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'SOLD', 'RENTED', 'ARCHIVED')),
    CONSTRAINT ck_properties_furnishing CHECK (furnishing IS NULL OR furnishing IN
        ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED')),
    CONSTRAINT ck_properties_price CHECK (price > 0),
    CONSTRAINT ck_properties_areas CHECK (
        (carpet_area_sqft IS NULL OR carpet_area_sqft > 0) AND
        (built_up_area_sqft IS NULL OR built_up_area_sqft > 0)),
    CONSTRAINT ck_properties_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_properties_lon CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_properties_floors CHECK (
        floor_number IS NULL OR total_floors IS NULL OR floor_number <= total_floors),
    -- A listing is only allowed to be ACTIVE once it has been published.
    CONSTRAINT ck_properties_published CHECK (status <> 'ACTIVE' OR published_at IS NOT NULL)
);

-- The workhorse index: almost every owner-facing query filters by owner.
CREATE INDEX ix_properties_owner ON properties (owner_id, status);
CREATE INDEX ix_properties_locality ON properties (locality_id) WHERE status = 'ACTIVE';
-- Reindex / backfill jobs walk the live set in publish order.
CREATE INDEX ix_properties_active_published ON properties (published_at DESC)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_properties_city_status ON properties (city, status);
-- Supports the incremental "what changed since last sync" reconciliation job.
CREATE INDEX ix_properties_updated_at ON properties (updated_at);

-- ---------------------------------------------------------------------------
-- property_amenities -- @ElementCollection of an enum
-- ---------------------------------------------------------------------------

CREATE TABLE property_amenities (
    property_id uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    amenity     varchar(32) NOT NULL,

    PRIMARY KEY (property_id, amenity),
    CONSTRAINT ck_property_amenities CHECK (amenity IN
        ('PARKING', 'LIFT', 'POWER_BACKUP', 'SECURITY', 'GYM', 'SWIMMING_POOL',
         'CLUBHOUSE', 'PARK', 'GAS_PIPELINE', 'WATER_SUPPLY_24X7', 'RAIN_WATER_HARVESTING',
         'VASTU_COMPLIANT', 'PET_FRIENDLY', 'WHEELCHAIR_ACCESSIBLE', 'GATED_COMMUNITY'))
);

-- ---------------------------------------------------------------------------
-- property_images
-- ---------------------------------------------------------------------------

CREATE TABLE property_images (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    url         text        NOT NULL,
    caption     varchar(180),
    position    smallint    NOT NULL DEFAULT 0,
    width       integer,
    height      integer,
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_property_images_position UNIQUE (property_id, position)
);

CREATE INDEX ix_property_images_property ON property_images (property_id);

-- ---------------------------------------------------------------------------
-- leads -- an enquiry raised against a listing
-- ---------------------------------------------------------------------------

CREATE TABLE leads (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id     uuid         NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    seeker_id       uuid         REFERENCES users (id) ON DELETE SET NULL,
    name            varchar(120) NOT NULL,
    email           citext       NOT NULL,
    phone           varchar(20)  NOT NULL,
    message         varchar(1000),
    status          varchar(16)  NOT NULL DEFAULT 'NEW',
    source          varchar(32)  NOT NULL DEFAULT 'WEB',
    -- Client-supplied key that makes lead submission safely retryable.
    idempotency_key varchar(80),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,

    CONSTRAINT ck_leads_status CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'CLOSED', 'SPAM'))
);

CREATE INDEX ix_leads_property ON leads (property_id, created_at DESC);
CREATE INDEX ix_leads_seeker ON leads (seeker_id, created_at DESC);
-- Partial unique index: dedupes retries without forcing every lead to carry a key.
CREATE UNIQUE INDEX uq_leads_idempotency ON leads (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- saved_searches -- a stored filter set, optionally alerting
-- ---------------------------------------------------------------------------

CREATE TABLE saved_searches (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            varchar(120) NOT NULL,
    -- The search criteria verbatim, so replaying an alert uses the exact same
    -- query the user built. jsonb (not json) so we can index into it later.
    criteria        jsonb        NOT NULL,
    alert_frequency varchar(16)  NOT NULL DEFAULT 'NONE',
    last_alerted_at timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,

    CONSTRAINT uq_saved_searches_name UNIQUE (user_id, name),
    CONSTRAINT ck_saved_searches_freq CHECK (alert_frequency IN ('NONE', 'INSTANT', 'DAILY', 'WEEKLY'))
);

CREATE INDEX ix_saved_searches_due ON saved_searches (alert_frequency, last_alerted_at)
    WHERE alert_frequency <> 'NONE';

-- ---------------------------------------------------------------------------
-- outbox_events -- transactional outbox
-- ---------------------------------------------------------------------------
-- Rows are written in the SAME transaction as the aggregate change. A relay
-- polls PENDING rows and publishes them, which is what lets us keep Postgres
-- and Elasticsearch consistent without a dual write.

CREATE TABLE outbox_events (
    id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    aggregate_type varchar(48) NOT NULL,
    aggregate_id   varchar(64) NOT NULL,
    event_type     varchar(64) NOT NULL,
    payload        jsonb       NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,

    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index keeps the relay's hot query proportional to the backlog,
-- not to the (ever-growing) full event log.
CREATE INDEX ix_outbox_pending ON outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id, id DESC);
