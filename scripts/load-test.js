/**
 * Load test for the read path.
 *
 *   brew install k6
 *   k6 run scripts/load-test.js
 *
 * Measures what a real visitor actually does: search with varied filters, then
 * open a listing. Those two are ~90% of production traffic on a portal, and they
 * exercise the parts worth measuring — Elasticsearch aggregations on every
 * search, and the Redis cache-aside on detail.
 *
 * Run `python3 scripts/seed-demo.py` first. Against an empty index every query
 * returns nothing and the numbers are meaningless.
 *
 * Env:
 *   BASE_URL   default http://localhost:8080
 *   PROFILE    smoke | load | stress   (default load)
 */

import http from "k6/http";
import { check, group, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

/*
 * 429 is a correct response, not a failed request.
 *
 * Without this k6 counts every rate-limited call as a failure and http_req_failed
 * reports ~73%, which looks like the API falling over when it is in fact
 * defending itself.
 */
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PROFILE = __ENV.PROFILE || "load";

// Tracked separately because they have genuinely different cost profiles, and
// an aggregate p95 across both would hide which one is slow.
const searchDuration = new Trend("nivas_search_duration", true);
const detailDuration = new Trend("nivas_detail_duration", true);
const facetedDuration = new Trend("nivas_faceted_search_duration", true);
const geoDuration = new Trend("nivas_geo_search_duration", true);
const rateLimited = new Rate("nivas_rate_limited");

const PROFILES = {
  // Does it work at all — used in CI, not for numbers.
  smoke: { stages: [{ duration: "20s", target: 3 }] },

  // The headline profile. Ramp, hold, ramp down.
  load: {
    stages: [
      { duration: "30s", target: 20 },
      { duration: "1m30s", target: 20 },
      { duration: "30s", target: 50 },
      { duration: "1m", target: 50 },
      { duration: "30s", target: 0 },
    ],
  },

  // Push until something gives. Expect the rate limiter to start rejecting —
  // that is the system working, not failing.
  stress: {
    stages: [
      { duration: "30s", target: 50 },
      { duration: "1m", target: 150 },
      { duration: "1m", target: 300 },
      { duration: "30s", target: 0 },
    ],
  },
};

export const options = {
  ...PROFILES[PROFILE],
  thresholds: {
    // Deliberately on the custom trends, not http_req_duration: a threshold
    // averaged over cheap and expensive endpoints tells you nothing.
    nivas_search_duration: ["p(95)<400", "p(99)<800"],
    nivas_faceted_search_duration: ["p(95)<500"],
    nivas_detail_duration: ["p(95)<150"], // cache-aside; a miss is the tail
    nivas_geo_search_duration: ["p(95)<500"],
    // 429s are excluded from failure: rejecting excess load is correct behaviour.
    http_req_failed: ["rate<0.01"],
  },
  summaryTrendStats: ["min", "med", "p(95)", "p(99)", "max"],
};

const CITIES = ["Bengaluru", "Mumbai", "Pune", "Hyderabad", "Chennai", "Gurugram", "Noida", "Delhi"];
const QUERIES = [
  "2 bhk koramangala",
  "villa with pool",
  "apartment near metro",
  "furnished flat",
  "gated community",
  "penthouse",
  "studio",
  "independent house",
];
const AMENITIES = ["PARKING", "LIFT", "GYM", "POWER_BACKUP", "SWIMMING_POOL", "SECURITY"];

// Bengaluru localities, so geo queries land somewhere with data.
const POINTS = [
  { lat: 12.9352, lon: 77.6245 }, // Koramangala
  { lat: 12.9784, lon: 77.6408 }, // Indiranagar
  { lat: 12.9116, lon: 77.6474 }, // HSR Layout
  { lat: 12.9698, lon: 77.75 }, // Whitefield
];

/**
 * Builds a query string.
 *
 * k6's JS runtime has no web APIs, so URLSearchParams is not available — it
 * throws ReferenceError at runtime rather than failing to parse, which means a
 * script can look perfectly fine until it is actually run under load.
 *
 * Takes an array of [key, value] pairs rather than an object so a key can
 * repeat, which is how list parameters are bound (amenities=A&amenities=B).
 */
function qs(pairs) {
  return pairs
    .filter(([, value]) => value !== undefined && value !== null && value !== "")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join("&");
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

/**
 * Collected once at startup and shared with every VU, so the detail test hits
 * ids that actually exist. Doing this per-iteration would measure the setup
 * rather than the endpoint.
 */
export function setup() {
  const response = http.get(`${BASE_URL}/api/v1/search?size=50&sort=NEWEST`);

  if (response.status !== 200) {
    throw new Error(
      `Could not reach the API at ${BASE_URL} (status ${response.status}). ` +
        `Start it before running the load test.`,
    );
  }

  const ids = (response.json("results.content") || []).map((item) => item.id);
  if (ids.length === 0) {
    throw new Error(
      "The search index is empty. Run `python3 scripts/seed-demo.py` first, " +
        "otherwise every query returns nothing and the numbers are meaningless.",
    );
  }

  console.log(`Loaded ${ids.length} listing ids for the detail path.`);
  return { ids };
}

/*
 * Each virtual user presents as a distinct client.
 *
 * The limiter keys anonymous callers by X-Forwarded-For, and a real deployment
 * sits behind a proxy that sets it per client. Without this every VU shares the
 * host's single IP and therefore one 60-requests-per-minute bucket, so the run
 * measures rate-limit rejections — a Redis round trip that never touches
 * Elasticsearch — rather than search. The first run of this script reported a
 * flattering p95 of 6ms with 73% of requests rejected.
 */
function clientHeaders() {
  return { "X-Forwarded-For": `10.${__VU % 256}.${(__VU >> 8) % 256}.1` };
}

function record(response, trend) {
  // A rejected request costs one Redis call and no search, so folding it into
  // the latency trend would quietly flatter every percentile.
  if (response.status !== 429) {
    trend.add(response.timings.duration);
  }
  if (response.status === 429) {
    rateLimited.add(1);
    return true;
  }
  rateLimited.add(0);
  return false;
}

export default function (data) {
  group("plain search", () => {
    const response = http.get(
      `${BASE_URL}/api/v1/search?${qs([["q", pick(QUERIES)], ["size", "24"]])}`,
      { tags: { name: "search" }, headers: clientHeaders() },
    );
    if (!record(response, searchDuration)) {
      check(response, {
        "search 200": (r) => r.status === 200,
        "returns facets": (r) => r.json("facets") !== undefined,
      });
    }
  });

  sleep(Math.random() * 0.5);

  group("faceted search", () => {
    // The expensive shape: text, several filters, and aggregations over them.
    const params = qs([
      ["q", pick(QUERIES)],
      ["city", pick(CITIES)],
      ["listingType", Math.random() < 0.7 ? "RENT" : "SALE"],
      ["minPrice", "15000"],
      ["maxPrice", "90000"],
      ["size", "24"],
      ["bedrooms", "2"],
      ["bedrooms", "3"],
      ["amenities", pick(AMENITIES)],
    ]);

    const response = http.get(`${BASE_URL}/api/v1/search?${params}`, {
      tags: { name: "faceted_search" },
      headers: clientHeaders(),
    });
    if (!record(response, facetedDuration)) {
      check(response, { "faceted 200": (r) => r.status === 200 });
    }
  });

  sleep(Math.random() * 0.5);

  group("geo search", () => {
    const point = pick(POINTS);
    const response = http.get(
      `${BASE_URL}/api/v1/search?${qs([
        ["lat", String(point.lat)],
        ["lon", String(point.lon)],
        ["radiusKm", "5"],
        ["sort", "DISTANCE"],
        ["size", "24"],
      ])}`,
      { tags: { name: "geo_search" }, headers: clientHeaders() },
    );
    if (!record(response, geoDuration)) {
      check(response, { "geo 200": (r) => r.status === 200 });
    }
  });

  sleep(Math.random() * 0.5);

  group("listing detail", () => {
    // Repeats are intentional: the point is to exercise the cache, and a real
    // audience concentrates heavily on a small set of popular listings.
    const id = pick(data.ids);
    const response = http.get(`${BASE_URL}/api/v1/properties/${id}`, {
      tags: { name: "detail" },
      headers: clientHeaders(),
    });
    if (!record(response, detailDuration)) {
      check(response, {
        "detail 200": (r) => r.status === 200,
        "has images": (r) => Array.isArray(r.json("images")),
      });
    }
  });

  sleep(1 + Math.random());
}

export function teardown() {
  console.log(
    "\nDone. Compare against actuator:\n" +
      `  curl -s ${BASE_URL}/actuator/metrics/cache.gets\n` +
      `  curl -s ${BASE_URL}/actuator/metrics/nivas.outbox.backlog\n`,
  );
}
