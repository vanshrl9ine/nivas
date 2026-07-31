#!/usr/bin/env python3
"""
Populates a running Nivas instance with demo data.

    python3 scripts/seed-demo.py

Deliberately talks to the public HTTP API rather than writing to Postgres
directly. Two reasons:

  * A direct INSERT would produce listings that exist but were never published
    through the state machine, so no outbox event is ever written and nothing
    reaches the search index. The app would look broken in exactly the way this
    architecture exists to prevent.
  * Going through the API means this doubles as an end-to-end smoke test. If the
    outbox relay, Kafka consumer or Elasticsearch mapping is broken, the
    "waiting for the index" step below says so instead of leaving you to
    discover it in the UI.

Standard library only, so there is nothing to install.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

DEFAULT_BASE_URL = "http://localhost:8080"

# Fixed seed so re-running produces the same catalogue. Debugging "why does this
# listing rank above that one" is impossible if the data changes every run.
random.seed(20260731)

OWNER = {
    "email": "owner@nivas.demo",
    "password": "demo-password-1234",
    "fullName": "Priya Sharma",
    "phone": "+919876543210",
    "role": "OWNER",
}

SEEKER = {
    "email": "seeker@nivas.demo",
    "password": "demo-password-1234",
    "fullName": "Arjun Mehta",
    "phone": "+919812345678",
    "role": "SEEKER",
}

# Stable Unsplash photo ids, so the same listing always gets the same photos.
PHOTOS = [
    "photo-1522708323590-d24dbb6b0267",
    "photo-1560448204-e02f11c3d0e2",
    "photo-1502672260266-1c1ef2d93688",
    "photo-1493809842364-78817add7ffb",
    "photo-1484154218962-a197022b5858",
    "photo-1512917774080-9991f1c4c750",
    "photo-1567496898669-ee935f5f647a",
    "photo-1600596542815-ffad4c1539a9",
    "photo-1600585154340-be6161a56a0c",
    "photo-1600607687939-ce8a6c25118c",
]

AMENITY_POOL = [
    "PARKING", "LIFT", "POWER_BACKUP", "SECURITY", "GYM", "SWIMMING_POOL",
    "CLUBHOUSE", "PARK", "GAS_PIPELINE", "WATER_SUPPLY_24X7",
    "RAIN_WATER_HARVESTING", "VASTU_COMPLIANT", "PET_FRIENDLY", "GATED_COMMUNITY",
]

# Rough per-city rent for a 2 BHK, used to scale everything else. Made up, but
# in the right order of magnitude so price filters and facets behave sensibly.
CITY_RENT_BASE = {
    "Bengaluru": 38_000,
    "Mumbai": 72_000,
    "Delhi": 42_000,
    "Gurugram": 45_000,
    "Noida": 30_000,
    "Hyderabad": 32_000,
    "Pune": 30_000,
    "Chennai": 28_000,
}

RESIDENTIAL_TYPES = ["APARTMENT", "INDEPENDENT_HOUSE", "VILLA", "STUDIO", "PENTHOUSE"]
FURNISHINGS = ["UNFURNISHED", "SEMI_FURNISHED", "FULLY_FURNISHED"]

ADJECTIVES = ["Bright", "Spacious", "Quiet", "Modern", "Airy", "Well-kept", "Sunlit", "Roomy"]
FEATURES = [
    "with a large balcony", "near the metro", "in a gated community",
    "with covered parking", "overlooking the park", "on a high floor",
    "with a modular kitchen", "close to the tech park",
]


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str, path: str):
        super().__init__(f"{status} on {path}: {body[:400]}")
        self.status = status
        self.body = body


@dataclass
class Client:
    base_url: str
    access_token: str | None = None
    refresh_token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: Any = None,
        anonymous: bool = False,
        headers: dict[str, str] | None = None,
    ) -> Any:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode() if body is not None else None

        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.access_token and not anonymous:
            request.add_header("Authorization", f"Bearer {self.access_token}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read().decode()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise ApiError(error.code, error.read().decode(errors="replace"), path) from None
        except urllib.error.URLError as error:
            raise RuntimeError(
                f"Could not reach the API at {self.base_url} ({error.reason}).\n"
                f"Start it with:\n"
                f"  docker compose -f infra/docker-compose.yml up -d\n"
                f"  cd nivas-api && ./mvnw spring-boot:run"
            ) from None

    def get(self, path: str, **kwargs) -> Any:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, body: Any = None, **kwargs) -> Any:
        return self.request("POST", path, body, **kwargs)

    def patch(self, path: str, body: Any = None, **kwargs) -> Any:
        return self.request("PATCH", path, body, **kwargs)


def log(message: str) -> None:
    print(message, flush=True)


def wait_for_api(client: Client, timeout_seconds: int = 90) -> None:
    log(f"Waiting for the API at {client.base_url} …")
    deadline = time.monotonic() + timeout_seconds

    while time.monotonic() < deadline:
        try:
            health = client.get("/actuator/health", anonymous=True)
            if health and health.get("status") == "UP":
                log("  API is up.")
                return
            log(f"  status={health.get('status') if health else 'unknown'}, retrying …")
        except Exception:
            pass
        time.sleep(2)

    raise RuntimeError(
        "The API did not report UP in time. Check that Postgres, Redis, "
        "Elasticsearch and Kafka are all running."
    )


def sign_in(client: Client, account: dict) -> dict:
    """Registers the account, or logs in if a previous run already created it."""
    try:
        tokens = client.post("/api/v1/auth/register", account, anonymous=True)
        log(f"  registered {account['email']}")
    except ApiError as error:
        if error.status != 409:
            raise
        tokens = client.post(
            "/api/v1/auth/login",
            {"email": account["email"], "password": account["password"]},
            anonymous=True,
        )
        log(f"  signed in as {account['email']} (already existed)")

    client.access_token = tokens["accessToken"]
    client.refresh_token = tokens["refreshToken"]
    return tokens["user"]


def load_localities(client: Client) -> dict[str, list[dict]]:
    cities = client.get("/api/v1/localities/cities", anonymous=True)
    if not cities:
        raise RuntimeError(
            "No localities found. The V2 seed migration should have created them — "
            "check that Flyway ran against this database."
        )

    by_city: dict[str, list[dict]] = {}
    for city in cities:
        localities = client.get(f"/api/v1/localities?city={urllib.parse.quote(city)}", anonymous=True)
        if localities:
            by_city[city] = localities
    return by_city


def build_listing(locality: dict, index: int) -> dict:
    city = locality["city"]
    listing_type = "RENT" if index % 3 != 0 else "SALE"
    property_type = random.choice(RESIDENTIAL_TYPES)

    bedrooms = 0 if property_type == "STUDIO" else random.choice([1, 2, 2, 3, 3, 4])
    carpet_area = 380 if bedrooms == 0 else 420 + bedrooms * random.randint(260, 340)

    base_rent = CITY_RENT_BASE.get(city, 30_000)
    # Scale off the 2 BHK baseline, then jitter so prices are not suspiciously round.
    rent = base_rent * (0.55 + 0.28 * max(bedrooms, 1)) * random.uniform(0.82, 1.25)
    if property_type in ("VILLA", "PENTHOUSE"):
        rent *= 1.8

    if listing_type == "RENT":
        price = round(rent / 500) * 500
        deposit = price * random.choice([2, 3, 6])
        maintenance = round(random.uniform(1500, 6000) / 500) * 500
    else:
        # Very roughly 250x monthly rent, which is the right ballpark for India.
        price = round(rent * 250 / 100_000) * 100_000
        deposit = None
        maintenance = round(random.uniform(2000, 8000) / 500) * 500

    # Scatter listings around the locality centroid so geo-radius search has
    # something to actually discriminate between. ~0.02 deg is roughly 2 km.
    latitude = (locality.get("latitude") or 12.97) + random.uniform(-0.02, 0.02)
    longitude = (locality.get("longitude") or 77.59) + random.uniform(-0.02, 0.02)

    bedroom_label = "Studio" if bedrooms == 0 else f"{bedrooms} BHK"
    title = (
        f"{random.choice(ADJECTIVES)} {bedroom_label} "
        f"{random.choice(FEATURES)} in {locality['name']}"
    )

    photo_count = random.randint(3, 5)
    start = (index * 3) % len(PHOTOS)
    photos = [PHOTOS[(start + offset) % len(PHOTOS)] for offset in range(photo_count)]

    total_floors = random.randint(4, 22)

    return {
        "title": title[:180],
        "description": (
            f"A {bedroom_label.lower()} {property_type.replace('_', ' ').lower()} in "
            f"{locality['name']}, {city}. {random.choice(FEATURES).capitalize()}, "
            f"with good access to shops, schools and public transport. "
            f"Carpet area {carpet_area} sq.ft."
        ),
        "listingType": listing_type,
        "propertyType": property_type,
        "price": price,
        "maintenanceMonthly": maintenance,
        "securityDeposit": deposit,
        "priceNegotiable": random.random() < 0.4,
        "bedrooms": bedrooms,
        "bathrooms": max(1, bedrooms),
        "balconies": random.randint(0, 2),
        "furnishing": random.choice(FURNISHINGS),
        "carpetAreaSqft": carpet_area,
        "builtUpAreaSqft": int(carpet_area * random.uniform(1.15, 1.3)),
        "floorNumber": random.randint(0, total_floors),
        "totalFloors": total_floors,
        "ageYears": random.randint(0, 18),
        "localityId": locality["id"],
        "addressLine": f"{random.randint(1, 220)}, {locality['name']} Main Road",
        "city": city,
        "state": locality["state"],
        "pincode": f"{random.randint(110, 590)}{random.randint(100, 999)}",
        "latitude": round(latitude, 6),
        "longitude": round(longitude, 6),
        "amenities": sorted(random.sample(AMENITY_POOL, random.randint(3, 8))),
        "images": [
            {"url": f"https://images.unsplash.com/{photo}?w=1200&q=80", "caption": None}
            for photo in photos
        ],
    }


def publish(client: Client, property_id: str) -> None:
    """DRAFT -> PENDING_REVIEW -> ACTIVE. The state machine forbids skipping."""
    client.patch(f"/api/v1/properties/{property_id}/status", {"status": "PENDING_REVIEW"})
    client.patch(f"/api/v1/properties/{property_id}/status", {"status": "ACTIVE"})


def wait_for_index(client: Client, expected: int, timeout_seconds: int = 120) -> int:
    """
    Polls search until the index catches up.

    This is the step that proves the pipeline: a listing only appears here after
    its outbox row has been relayed to Kafka, consumed, and projected into
    Elasticsearch. If it never arrives, the failure is in one of those hops.
    """
    log(f"Waiting for the search index to reach {expected} listings …")
    deadline = time.monotonic() + timeout_seconds
    seen = 0

    while time.monotonic() < deadline:
        try:
            response = client.get("/api/v1/search?size=1", anonymous=True)
            seen = response["results"]["totalElements"]
            if seen >= expected:
                log(f"  indexed {seen} listings.")
                return seen
            log(f"  {seen}/{expected} …")
        except ApiError as error:
            if error.status == 429:
                # The search endpoint is rate limited; back off rather than
                # hammering it and reporting a false failure.
                log("  rate limited, backing off …")
                time.sleep(10)
                continue
            raise
        time.sleep(3)

    log(
        f"  WARNING: only {seen}/{expected} listings were indexed within "
        f"{timeout_seconds}s.\n"
        f"  The outbox relay or the Kafka consumer may be stuck. Check:\n"
        f"    curl -s localhost:8080/actuator/metrics/nivas.outbox.backlog\n"
        f"    docker compose -f infra/docker-compose.yml logs kafka elasticsearch"
    )
    return seen


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed a running Nivas instance with demo data.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--listings", type=int, default=36, help="how many listings to create")
    parser.add_argument("--skip-wait", action="store_true", help="do not wait for the API or the index")
    args = parser.parse_args()

    owner_client = Client(args.base_url)

    if not args.skip_wait:
        wait_for_api(owner_client)

    log("\nAccounts")
    owner = sign_in(owner_client, OWNER)

    seeker_client = Client(args.base_url)
    seeker = sign_in(seeker_client, SEEKER)

    log("\nLocalities")
    by_city = load_localities(owner_client)
    flat_localities = [locality for localities in by_city.values() for locality in localities]
    log(f"  {len(flat_localities)} localities across {len(by_city)} cities")

    log(f"\nCreating {args.listings} listings")
    created: list[dict] = []
    for index in range(args.listings):
        locality = flat_localities[index % len(flat_localities)]
        try:
            listing = owner_client.post("/api/v1/properties", build_listing(locality, index))
            publish(owner_client, listing["id"])
            created.append(listing)
            if (index + 1) % 6 == 0:
                log(f"  published {index + 1}/{args.listings}")
        except ApiError as error:
            log(f"  FAILED on listing {index + 1}: {error}")

    log(f"  {len(created)} published.")

    if not args.skip_wait and created:
        wait_for_index(owner_client, len(created))

    # A handful of enquiries, so the owner's inbox is not empty. The endpoint
    # allows five per hour per caller, so this stays under that deliberately.
    log("\nEnquiries")
    sent = 0
    for listing in created[:4]:
        try:
            seeker_client.post(
                f"/api/v1/properties/{listing['id']}/leads",
                {
                    "name": SEEKER["fullName"],
                    "email": SEEKER["email"],
                    "phone": SEEKER["phone"],
                    "message": "Is this still available? I'd like to visit this weekend.",
                    "source": "WEB",
                },
                headers={"Idempotency-Key": f"seed-{listing['id']}"},
            )
            sent += 1
        except ApiError as error:
            if error.status == 429:
                log("  hit the hourly enquiry limit, which is the limiter working.")
                break
            log(f"  FAILED: {error}")
    log(f"  {sent} enquiries sent.")

    log("\nSaved searches")
    saved_searches = [
        {
            "name": "2 BHK in Bengaluru under 45k",
            "criteria": {
                "city": "Bengaluru",
                "listingType": "RENT",
                "bedrooms": [2],
                "maxPrice": 45000,
            },
            "alertFrequency": "DAILY",
        },
        {
            "name": "Verified villas to buy",
            "criteria": {"listingType": "SALE", "propertyTypes": ["VILLA"], "verifiedOnly": True},
            "alertFrequency": "WEEKLY",
        },
        {
            "name": "Anything with a gym and parking",
            "criteria": {"amenities": ["GYM", "PARKING"]},
            "alertFrequency": "NONE",
        },
    ]
    for saved in saved_searches:
        try:
            seeker_client.post("/api/v1/saved-searches", saved)
            log(f"  {saved['name']}")
        except ApiError as error:
            if error.status == 409:
                log(f"  {saved['name']} (already existed)")
            else:
                log(f"  FAILED: {error}")

    log("\n" + "=" * 62)
    log("Done. Sign in at http://localhost:3000/login")
    log("")
    log(f"  Owner   {OWNER['email']} / {OWNER['password']}")
    log(f"          {owner['role']} — has listings and enquiries")
    log(f"  Seeker  {SEEKER['email']} / {SEEKER['password']}")
    log(f"          {seeker['role']} — has saved searches")
    log("")
    log("Try:")
    log("  http://localhost:3000/search?q=koramangala&bedrooms=2&listingType=RENT")
    log("  http://localhost:3000/search?lat=12.9352&lon=77.6245&radiusKm=5&sort=DISTANCE")
    log("=" * 62)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except RuntimeError as error:
        print(f"\n{error}", file=sys.stderr)
        sys.exit(1)
