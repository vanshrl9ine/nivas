-- Monetisation, placement inventory, and seller analytics.
--
-- Money rules that apply throughout:
--   * Amounts are in the smallest currency unit (paise), as bigint. Payment
--     providers work in minor units, and a rounding difference between us and
--     them is a reconciliation problem nobody wants.
--   * Anything a webhook can touch is idempotent by a unique key, because
--     webhook delivery is at-least-once.

-- Needed for the placement exclusion constraint: it mixes equality on scalar
-- columns with overlap on a range, and plain btree cannot do that.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- placement_surfaces -- the inventory that exists to be sold
-- ---------------------------------------------------------------------------
-- Capacity is the number of slots on a surface. It is reference data rather
-- than a constant in code because it is a commercial decision: widening the
-- city hero from one slot to three is a pricing change, not a deploy.

CREATE TABLE placement_surfaces (
    code          varchar(32) PRIMARY KEY,
    name          varchar(80) NOT NULL,
    -- true  -> slots exist per city   (/mumbai, /bengaluru)
    -- false -> a single national pool (the all-India home page)
    city_scoped   boolean     NOT NULL,
    capacity      smallint    NOT NULL,
    description   varchar(300),

    CONSTRAINT ck_placement_surfaces_capacity CHECK (capacity > 0)
);

INSERT INTO placement_surfaces (code, name, city_scoped, capacity, description) VALUES
    ('CITY_HOME_HERO',      'City home hero',      true,  1,
     'The single banner slot at the top of a city home page. An audience maximizer takes this.'),
    ('CITY_HOME_FEATURED',  'City home featured',  true,  6,
     'The featured listing rail on a city home page.'),
    ('NATIONAL_HOME_HERO',  'National home hero',  false, 3,
     'The rotating hero on the all-India home page.'),
    ('SEARCH_TOP',          'Top of search',       true,  3,
     'Promoted results above organic search hits.')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- packages -- what is for sale
-- ---------------------------------------------------------------------------

CREATE TABLE packages (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code              varchar(48)  NOT NULL,
    name              varchar(80)  NOT NULL,
    description       varchar(400),

    -- LISTING            grants the right to have listings live at all
    -- BOOST              buys placement slots on a surface
    -- AUDIENCE_MAXIMIZER buys every slot on a city home page, exclusively
    package_type      varchar(24)  NOT NULL,

    price_paise       bigint       NOT NULL,
    currency          varchar(3)   NOT NULL DEFAULT 'INR',
    duration_days     integer      NOT NULL,

    -- LISTING entitlements. Placement entitlements live in package_surfaces,
    -- because a package can span more than one surface — an audience maximizer
    -- takes the hero *and* every featured slot.
    listing_quota     integer,
    auto_verify       boolean      NOT NULL DEFAULT false,

    sort_order        smallint     NOT NULL DEFAULT 0,
    active            boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_packages_code UNIQUE (code),
    CONSTRAINT ck_packages_type CHECK (package_type IN ('LISTING', 'BOOST', 'AUDIENCE_MAXIMIZER')),
    CONSTRAINT ck_packages_price CHECK (price_paise >= 0),
    CONSTRAINT ck_packages_duration CHECK (duration_days > 0),
    -- Each type must carry the fields its own kind needs, and nothing is
    -- allowed to be half-configured.
    CONSTRAINT ck_packages_listing_fields CHECK (
        package_type <> 'LISTING' OR (listing_quota IS NOT NULL AND listing_quota > 0)),
    -- A free package must actually be free.
    CONSTRAINT ck_packages_free CHECK (code <> 'FREE_LISTING' OR price_paise = 0)
);

CREATE INDEX ix_packages_active ON packages (package_type, sort_order) WHERE active;

-- ---------------------------------------------------------------------------
-- package_surfaces -- how much inventory a package buys, and where
-- ---------------------------------------------------------------------------
-- One row per surface a package books slots on. A boost buys one slot on one
-- surface; an audience maximizer buys the hero *and* the whole featured rail,
-- which is exactly what makes it a takeover rather than a big boost.

CREATE TABLE package_surfaces (
    package_id uuid        NOT NULL REFERENCES packages (id) ON DELETE CASCADE,
    surface    varchar(32) NOT NULL REFERENCES placement_surfaces (code),
    slot_count smallint    NOT NULL,

    PRIMARY KEY (package_id, surface),
    CONSTRAINT ck_package_surfaces_slots CHECK (slot_count > 0)
);

-- ---------------------------------------------------------------------------
-- subscriptions -- the right to have listings live
-- ---------------------------------------------------------------------------

CREATE TABLE subscriptions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    package_id  uuid        NOT NULL REFERENCES packages (id),
    status      varchar(16) NOT NULL DEFAULT 'ACTIVE',
    started_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz,
    order_id    uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT ck_subscriptions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_subscriptions_period CHECK (expires_at IS NULL OR expires_at > started_at)
);

-- At most one active subscription per user, enforced by the database. Two
-- concurrent payments must not both succeed in granting a plan.
CREATE UNIQUE INDEX uq_subscriptions_one_active ON subscriptions (user_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_subscriptions_expiry ON subscriptions (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- orders -- an intent to pay, and its outcome
-- ---------------------------------------------------------------------------

CREATE TABLE orders (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    package_id         uuid         NOT NULL REFERENCES packages (id),
    -- Which listing the purchase is for. Null for a pure LISTING subscription.
    property_id        uuid         REFERENCES properties (id) ON DELETE SET NULL,
    -- Which city a placement was bought for. Null for national surfaces.
    city               varchar(80),

    amount_paise       bigint       NOT NULL,
    currency           varchar(3)   NOT NULL DEFAULT 'INR',
    status             varchar(16)  NOT NULL DEFAULT 'CREATED',

    gateway            varchar(24)  NOT NULL,
    gateway_order_id   varchar(120),
    gateway_payment_id varchar(120),
    failure_reason     varchar(400),

    paid_at            timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0,

    CONSTRAINT ck_orders_status CHECK (status IN ('CREATED', 'PAID', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_orders_amount CHECK (amount_paise > 0),
    -- A paid order must record what paid it: makes reconciliation possible, and
    -- catches any code path marking an order paid without evidence.
    CONSTRAINT ck_orders_paid_has_payment CHECK (
        status <> 'PAID' OR (gateway_payment_id IS NOT NULL AND paid_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_orders_gateway_order ON orders (gateway, gateway_order_id)
    WHERE gateway_order_id IS NOT NULL;
CREATE INDEX ix_orders_user ON orders (user_id, created_at DESC);
CREATE INDEX ix_orders_pending ON orders (created_at) WHERE status = 'CREATED';

ALTER TABLE subscriptions
    ADD CONSTRAINT fk_subscriptions_order FOREIGN KEY (order_id) REFERENCES orders (id);

-- ---------------------------------------------------------------------------
-- payment_events -- every webhook received, verbatim
-- ---------------------------------------------------------------------------

CREATE TABLE payment_events (
    id               bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    gateway          varchar(24)  NOT NULL,
    event_id         varchar(120) NOT NULL,
    event_type       varchar(64)  NOT NULL,
    order_id         uuid REFERENCES orders (id) ON DELETE SET NULL,
    payload          jsonb        NOT NULL,
    signature_valid  boolean      NOT NULL,
    processed        boolean      NOT NULL DEFAULT false,
    processing_error text,
    received_at      timestamptz  NOT NULL DEFAULT now(),

    -- The provider's event id. This unique constraint is what makes a
    -- re-delivered webhook a no-op instead of a double fulfilment.
    CONSTRAINT uq_payment_events_id UNIQUE (gateway, event_id)
);

CREATE INDEX ix_payment_events_order ON payment_events (order_id, received_at DESC);

-- ---------------------------------------------------------------------------
-- placements -- a listing occupying a slot, for a period
-- ---------------------------------------------------------------------------
-- One row per slot per booking. An audience maximizer does not get a special
-- flag: it simply books every slot on the surface, which is what "takeover"
-- means. That keeps exclusivity and ordinary boosts under one rule.

CREATE TABLE placements (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id  uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    order_id     uuid        REFERENCES orders (id) ON DELETE SET NULL,
    package_id   uuid        NOT NULL REFERENCES packages (id),

    surface      varchar(32) NOT NULL REFERENCES placement_surfaces (code),
    -- Empty string rather than NULL for national surfaces: the exclusion
    -- constraint compares with =, and NULL is never equal to NULL, which would
    -- silently let every national booking overlap every other one.
    city         varchar(80) NOT NULL DEFAULT '',
    slot_index   smallint    NOT NULL,

    starts_on    date        NOT NULL,
    ends_on      date        NOT NULL,
    -- Generated from the two dates rather than written directly, so an ORM can
    -- insert a row with ordinary columns while the exclusion constraint still
    -- gets a real range to work with.
    --
    -- Half-open [start, end): a booking ending on the 5th and one starting on
    -- the 5th do not overlap, which is what a seller expects.
    period       daterange   GENERATED ALWAYS AS (daterange(starts_on, ends_on, '[)')) STORED,
    status       varchar(16) NOT NULL DEFAULT 'ACTIVE',

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,

    CONSTRAINT ck_placements_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_placements_slot CHECK (slot_index >= 0),
    CONSTRAINT ck_placements_period CHECK (ends_on > starts_on),

    -- The heart of the inventory model: the same slot on the same surface in
    -- the same city cannot be sold twice for overlapping dates.
    --
    -- Enforced here rather than by a check-then-insert in the service, because
    -- that check is a race — two buyers hitting checkout at the same moment
    -- would both read "free" and both write. The database rejects the second.
    CONSTRAINT ex_placements_no_double_booking EXCLUDE USING gist (
        surface WITH =,
        city WITH =,
        slot_index WITH =,
        period WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE INDEX ix_placements_lookup ON placements (surface, city, period)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_placements_property ON placements (property_id, status);

-- ---------------------------------------------------------------------------
-- property_view_daily -- the time series behind the seller dashboard
-- ---------------------------------------------------------------------------
-- properties.view_count is a running total and cannot answer "how did this do
-- last week". The Redis trending sets expire after eight days and are ranked
-- globally rather than per listing. Neither gives a seller a chart.

CREATE TABLE property_view_daily (
    property_id uuid    NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    day         date    NOT NULL,
    views       integer NOT NULL DEFAULT 0,

    PRIMARY KEY (property_id, day),
    CONSTRAINT ck_property_view_daily_views CHECK (views >= 0)
);

CREATE INDEX ix_property_view_daily_day ON property_view_daily (day, property_id);

-- ---------------------------------------------------------------------------
-- seed: the packages
-- ---------------------------------------------------------------------------
-- Reference data, so it belongs in a migration. Prices are illustrative.

INSERT INTO packages (code, name, description, package_type, price_paise, duration_days,
                      listing_quota, auto_verify, sort_order) VALUES
    ('FREE_LISTING',      'Free listing',
     'One live listing, so you can try the platform before paying for it.',
     'LISTING', 0, 365, 1, false, 0),

    ('LISTING_5',         'Listing pack (5)',
     'Five live listings and a verified badge.',
     'LISTING', 99900, 90, 5, true, 1),

    ('LISTING_25',        'Listing pack (25)',
     'Twenty-five live listings for a working agent.',
     'LISTING', 349900, 90, 25, true, 2),

    ('BOOST_SEARCH',      'Boost in search',
     'Your listing appears above organic results for its city.',
     'BOOST', 49900, 30, NULL, false, 10),

    ('BOOST_CITY_HOME',   'Feature on the city home page',
     'A slot in the featured rail on your city home page.',
     'BOOST', 79900, 30, NULL, false, 11),

    ('AUDIENCE_MAXIMIZER','Audience Maximizer',
     'Your listing takes over the entire home page for a city: the hero banner and every featured slot, exclusively, for the whole period.',
     'AUDIENCE_MAXIMIZER', 2499900, 14, NULL, true, 20)
ON CONFLICT (code) DO NOTHING;

-- How much inventory each package buys.
--
-- The audience maximizer is the interesting row: it books the hero *and* all
-- six featured slots, which is what turns it from a large boost into an
-- exclusive takeover. Nothing in the code special-cases it — the exclusion
-- constraint on placements does the rest, because once all the slots are taken
-- there is nothing left to sell.
INSERT INTO package_surfaces (package_id, surface, slot_count)
SELECT p.id, s.surface, s.slot_count
FROM packages p
JOIN (VALUES
    ('BOOST_SEARCH',       'SEARCH_TOP',         1::smallint),
    ('BOOST_CITY_HOME',    'CITY_HOME_FEATURED', 1::smallint),
    ('AUDIENCE_MAXIMIZER', 'CITY_HOME_HERO',     1::smallint),
    ('AUDIENCE_MAXIMIZER', 'CITY_HOME_FEATURED', 6::smallint)
) AS s(package_code, surface, slot_count) ON s.package_code = p.code
ON CONFLICT (package_id, surface) DO NOTHING;
