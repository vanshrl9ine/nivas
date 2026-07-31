#!/usr/bin/env python3
"""
Exercises the monetisation flow end to end against a running instance.

    python3 scripts/demo-billing.py

Walks the whole path a paying seller takes:

  1. the free plan's one-listing quota actually blocks a second listing
  2. buying a listing pack lifts it
  3. buying an Audience Maximizer takes over a city home page
  4. the same slot cannot then be sold to anybody else
  5. a replayed webhook does not fulfil twice
  6. a webhook with a bad signature is refused

No real money and no provider account: the mock gateway signs webhooks with real
HMAC-SHA256, so the verification path exercised here is the one production uses.

Standard library only.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any

DEFAULT_BASE = "http://localhost:8080"
# Must match nivas.payments.webhook-secret.
DEFAULT_SECRET = "dev-only-webhook-secret-change-me"

PASS = "  \033[32mPASS\033[0m"
FAIL = "  \033[31mFAIL\033[0m"


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url
        self.token: str | None = None

    def call(self, method: str, path: str, body: Any = None,
             headers: dict[str, str] | None = None, raw_body: str | None = None):
        url = f"{self.base_url}{path}"
        data = (raw_body.encode() if raw_body is not None
                else json.dumps(body).encode() if body is not None else None)

        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode()
                return response.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as error:
            text = error.read().decode(errors="replace")
            try:
                return error.code, json.loads(text)
            except json.JSONDecodeError:
                return error.code, {"raw": text}
        except urllib.error.URLError as error:
            raise SystemExit(
                f"Could not reach {self.base_url} ({error.reason}). Is the API running?")

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body, **kw)

    def patch(self, path, body=None, **kw):
        return self.call("PATCH", path, body, **kw)


results: list[tuple[bool, str]] = []


def check(condition: bool, description: str, detail: str = "") -> bool:
    results.append((condition, description))
    print(f"{PASS if condition else FAIL}  {description}")
    if detail and not condition:
        print(f"        {detail}")
    return condition


def sign(body: str, secret: str) -> str:
    return hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()


def webhook_body(gateway_order_id: str, captured: bool = True, event_id: str | None = None) -> str:
    """A payload shaped like the provider's, which the mock gateway parses."""
    return json.dumps({
        "id": event_id or f"evt_{uuid.uuid4().hex[:16]}",
        "event": "payment.captured" if captured else "payment.failed",
        "payload": {"payment": {"entity": {
            "id": f"pay_{uuid.uuid4().hex[:14]}",
            "order_id": gateway_order_id,
            "error_description": None if captured else "Card declined",
        }}},
    })


def login(api: Api, email: str, password: str) -> dict:
    status, body = api.post("/api/v1/auth/login", {"email": email, "password": password})
    if status != 200:
        raise SystemExit(f"Could not sign in as {email}: {body}")
    api.token = body["accessToken"]
    return body["user"]


def register_fresh_seller(api: Api) -> dict:
    """
    A brand-new owner for the quota walkthrough.

    Deliberately not the demo account. That one has 36 live listings, which is
    more than any pack grants, so "buying a bigger plan lets me publish" could
    never be true for it — the quota would still, correctly, refuse. Testing an
    entitlement rule against pre-existing state proves nothing about the rule.
    """
    email = f"seller-{uuid.uuid4().hex[:10]}@nivas.demo"
    password = "demo-password-1234"
    status, body = api.post("/api/v1/auth/register", {
        "email": email, "password": password,
        "fullName": "Quota Demo Seller", "role": "OWNER",
    })
    if status != 201:
        raise SystemExit(f"Could not register a demo seller: {body}")
    api.token = body["accessToken"]
    return body["user"]


def create_listing(api: Api, title: str, city: str = "Bengaluru") -> str:
    """A complete, publishable listing."""
    status, body = api.post("/api/v1/properties", {
        "title": title,
        "description": "A well-kept home close to shops, transport and parks.",
        "listingType": "RENT", "propertyType": "APARTMENT",
        "price": 38000, "priceNegotiable": True,
        "bedrooms": 2, "bathrooms": 2, "balconies": 1,
        "furnishing": "SEMI_FURNISHED",
        "carpetAreaSqft": 1100, "builtUpAreaSqft": 1350,
        "city": city, "state": "Karnataka", "pincode": "560095",
        "latitude": 12.9352, "longitude": 77.6245,
        "amenities": ["PARKING", "LIFT"],
        "images": [{"url": f"https://cdn.nivas.test/{i}.jpg"} for i in range(1, 4)],
    })
    if status != 201:
        raise SystemExit(f"Could not create a listing: {body}")
    return body["id"]


def publish(api: Api, property_id: str):
    api.patch(f"/api/v1/properties/{property_id}/status", {"status": "PENDING_REVIEW"})
    return api.patch(f"/api/v1/properties/{property_id}/status", {"status": "ACTIVE"})


def wait_until_indexed(api: Api, property_id: str, timeout_seconds: int = 30) -> bool:
    """
    Blocks until a listing reaches the search index.

    Publishing is deliberately asynchronous — Postgres, then outbox, then Kafka,
    then Elasticsearch — so anything that reads the index has to wait. The home
    page hydrates promoted listings from the index, which means a takeover
    bought a millisecond ago legitimately has nothing to show yet.
    """
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        _, found = api.get("/api/v1/search?" + urllib.parse.urlencode({"size": 50}))
        if any(item["id"] == property_id for item in found["results"]["content"]):
            return True
        time.sleep(1)
    return False


def pay(api: Api, secret: str, package_code: str, property_id: str | None = None,
        city: str | None = None) -> tuple[int, Any]:
    """Checkout, then deliver a signed 'captured' webhook, as the provider would."""
    request: dict[str, Any] = {"packageCode": package_code}
    if property_id:
        request["propertyId"] = property_id
    if city:
        request["city"] = city

    status, order = api.post("/api/v1/checkout", request)
    if status != 201:
        return status, order

    body = webhook_body(order["gatewayOrderId"])
    api.post("/api/v1/payments/webhook", raw_body=body,
             headers={"X-Nivas-Signature": sign(body, secret)})
    return status, order


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--secret", default=DEFAULT_SECRET)
    args = parser.parse_args()

    api = Api(args.base_url)
    owner = login(api, "owner@nivas.demo", "demo-password-1234")
    print(f"\nSigned in as {owner['email']} ({owner['role']})\n")

    # ---------------------------------------------------------------- quota
    print("The free plan's one-listing quota")
    register_fresh_seller(api)

    _, ent = api.get("/api/v1/me/entitlements")
    check(ent["packageCode"] == "FREE_LISTING", "a new seller starts on the free plan")
    check(ent["listingsAllowed"] == 1, "which allows exactly one live listing")
    check(ent["listingsUsed"] == 0, "with none used yet")

    first = create_listing(api, "Bright 2 BHK with covered parking in Koramangala")
    status, _ = publish(api, first)
    check(status == 200, "the first listing publishes")

    second = create_listing(api, "Quiet 2 BHK near the park in Koramangala")
    status, body = publish(api, second)
    check(status == 409, "the second is refused with 409", f"got {status}: {body}")
    check("upgrade" in json.dumps(body).lower(),
          "and the message says what to do about it")

    # ------------------------------------------------------------- upgrade
    print("\nBuying a listing pack")
    status, _ = pay(api, args.secret, "LISTING_5")
    check(status == 201, "checkout created an order")

    _, ent = api.get("/api/v1/me/entitlements")
    check(ent["packageCode"] == "LISTING_5", "the webhook granted the new plan")
    check(ent["listingsAllowed"] == 5, "quota lifted to five")

    status, _ = publish(api, second)
    check(status == 200, "the second listing publishes now")

    _, detail = api.get(f"/api/v1/properties/{second}")
    check(detail.get("verified") is True,
          "and a paid plan carries the verified badge")

    # ------------------------------------------------------------ takeover
    print("\nAudience Maximizer: taking over a city")
    promoted = create_listing(api, "Spacious 3 BHK in Bandra West", "Mumbai")
    publish(api, promoted)
    check(wait_until_indexed(api, promoted),
          "the promoted listing reached the search index")

    _, before = api.get("/api/v1/home/Mumbai")
    check(not before["takeover"], "the city page starts un-taken-over")

    status, order = pay(api, args.secret, "AUDIENCE_MAXIMIZER", promoted, "Mumbai")
    check(status == 201, "the maximizer was purchased", f"got {status}: {order}")

    _, after = api.get("/api/v1/home/Mumbai")
    check(after["takeover"], "the city page is now a takeover")
    check(any(s["id"] == "takeover" for s in after["sections"]),
          "and renders a takeover section")

    hero = next((s for s in after["sections"] if s["id"] == "takeover"), None)
    check(hero and hero["listings"] and hero["listings"][0]["id"] == promoted,
          "showing the listing that paid for it")
    check(hero and hero["kind"] == "PROMOTED",
          "labelled as promoted, not as an editorial pick")

    # -------------------------------------------------------- sold out now
    print("\nThe inventory is genuinely exclusive")
    _, packages = api.get("/api/v1/packages?" + urllib.parse.urlencode({"city": "Mumbai"}))
    maximizer = next(p for p in packages if p["code"] == "AUDIENCE_MAXIMIZER")
    check(maximizer["freeSlots"].get("CITY_HOME_HERO") == 0,
          "the hero reports zero free slots")

    status, body = api.post("/api/v1/checkout",
                            {"packageCode": "AUDIENCE_MAXIMIZER",
                             "propertyId": promoted, "city": "Mumbai"})
    check(status == 409, "a second maximizer for Mumbai is refused",
          f"got {status}: {body}")

    # ------------------------------------------------------- webhook rules
    print("\nWebhook handling")
    status, fresh = api.post("/api/v1/checkout", {"packageCode": "LISTING_25"})
    gateway_order_id = fresh["gatewayOrderId"]

    body = webhook_body(gateway_order_id)
    signature = sign(body, args.secret)

    _, first = api.post("/api/v1/payments/webhook", raw_body=body,
                        headers={"X-Nivas-Signature": signature})
    check(first.get("fulfilled") is True, "a correctly signed webhook fulfils the order",
          f"response was {first}")

    _, replay = api.post("/api/v1/payments/webhook", raw_body=body,
                         headers={"X-Nivas-Signature": signature})
    check(replay.get("fulfilled") is False,
          "the identical webhook replayed does not fulfil twice", f"response was {replay}")

    status, forged = api.post("/api/v1/checkout", {"packageCode": "LISTING_5"})
    bad = webhook_body(forged["gatewayOrderId"])
    _, rejected = api.post("/api/v1/payments/webhook", raw_body=bad,
                           headers={"X-Nivas-Signature": "0" * 64})
    check(rejected.get("fulfilled") is False, "a wrongly signed webhook is refused",
          f"response was {rejected}")

    _, ent = api.get("/api/v1/me/entitlements")
    check(ent["packageCode"] == "LISTING_25",
          "the forged webhook did not downgrade or grant anything")

    return summarise()


def summarise() -> int:
    passed = sum(1 for ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 60)
    print(f"{passed}/{total} checks passed")
    if passed < total:
        print("\nFailed:")
        for ok, description in results:
            if not ok:
                print(f"  - {description}")
    print("=" * 60)
    return 0 if passed == total else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
