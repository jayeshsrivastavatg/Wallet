#!/usr/bin/env python3
"""
One-command live concurrency probe for the Wallet service.

Usage:
    python3 scripts/burst_test.py
    python3 scripts/burst_test.py https://wallet-ihcp.onrender.com

The deployed demo database must be seeded once with scripts/seed_burst.sql.
Only Python's standard library is used.
"""

import json
import os
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE_URL = (sys.argv[1] if len(sys.argv) > 1
            else os.getenv("WALLET_BASE_URL", "https://wallet-ihcp.onrender.com")).rstrip("/")

ALICE_TOKEN = os.getenv("BURST_ALICE_TOKEN", "burst-alice-token")
BOB_TOKEN = os.getenv("BURST_BOB_TOKEN", "burst-bob-token")
NEW_USER_TOKEN = os.getenv("BURST_NEW_USER_TOKEN", "burst-new-user-token")

N = int(os.getenv("BURST_WALLET_REQUESTS", "20"))
K = int(os.getenv("BURST_RETRY_REQUESTS", "20"))
M = int(os.getenv("BURST_TRANSFER_REQUESTS", "40"))
TIMEOUT = int(os.getenv("BURST_TIMEOUT_SECONDS", "60"))


def http_json(method, path, token=None, body=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")

    request = Request(BASE_URL + path, data=data, headers=headers, method=method)

    try:
        with urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return error.code, payload
    except URLError as error:
        raise RuntimeError(f"Request failed: {error}") from error


def post_wallet(token):
    return http_json("POST", "/wallets", token=token)


def get_wallet(wallet_id, token):
    return http_json("GET", f"/wallets/{wallet_id}", token=token)


def post_transfer(token, from_wallet, to_wallet, amount, key):
    return http_json(
        "POST",
        "/transfers",
        token=token,
        body={
            "from": from_wallet,
            "to": to_wallet,
            "amount_paise": amount,
            "idempotency_key": key,
        },
    )


def concurrent_calls(count, fn):
    results = []
    with ThreadPoolExecutor(max_workers=min(count, 20)) as executor:
        futures = [executor.submit(fn, i) for i in range(count)]
        for future in as_completed(futures):
            results.append(future.result())
    return results


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wallet_balance(wallet_id, token):
    status, body = get_wallet(wallet_id, token)
    require(status == 200, f"GET wallet failed: HTTP {status}: {body}")
    return int(body["balancePaise"])


def test_concurrent_get_or_create():
    responses = concurrent_calls(N, lambda _: post_wallet(NEW_USER_TOKEN))

    require(all(status == 200 for status, _ in responses),
            f"Not all wallet requests succeeded: {responses}")

    wallet_ids = {body["walletId"] for _, body in responses}
    require(len(wallet_ids) == 1,
            f"Expected exactly one wallet, got {len(wallet_ids)}: {wallet_ids}")

    print(f"[PASS] concurrent get-or-create: {N} requests -> 1 wallet")
    return next(iter(wallet_ids))


def ensure_transfer_wallets():
    alice_status, alice = post_wallet(ALICE_TOKEN)
    bob_status, bob = post_wallet(BOB_TOKEN)

    require(alice_status == 200, f"Alice wallet failed: {alice_status}: {alice}")
    require(bob_status == 200, f"Bob wallet failed: {bob_status}: {bob}")

    return alice["walletId"], bob["walletId"]


def test_idempotent_retry_storm(alice_wallet, bob_wallet):
    amount = 100
    key = f"burst-idempotency-{uuid.uuid4()}"

    alice_before = wallet_balance(alice_wallet, ALICE_TOKEN)
    bob_before = wallet_balance(bob_wallet, BOB_TOKEN)

    responses = concurrent_calls(
        K,
        lambda _: post_transfer(
            ALICE_TOKEN, alice_wallet, bob_wallet, amount, key
        ),
    )

    require(all(status == 200 for status, _ in responses),
            f"Not all retry requests succeeded: {responses}")

    results = {(body.get("transfer_id"), body.get("status")) for _, body in responses}
    require(len(results) == 1,
            f"Expected identical retry results, got: {results}")

    transfer_id, transfer_status = next(iter(results))
    require(transfer_status == "SUCCESS",
            f"Expected SUCCESS transfer, got {transfer_status}")

    alice_after = wallet_balance(alice_wallet, ALICE_TOKEN)
    bob_after = wallet_balance(bob_wallet, BOB_TOKEN)

    require(alice_after == alice_before - amount,
            f"Alice was not debited exactly once: {alice_before} -> {alice_after}")
    require(bob_after == bob_before + amount,
            f"Bob was not credited exactly once: {bob_before} -> {bob_after}")

    print(
        f"[PASS] idempotent retry storm: {K} requests -> "
        f"1 transfer ({transfer_id}), one debit/credit"
    )


def test_conservation_under_contention(alice_wallet, bob_wallet):
    amount = 1

    alice_before = wallet_balance(alice_wallet, ALICE_TOKEN)
    bob_before = wallet_balance(bob_wallet, BOB_TOKEN)
    total_before = alice_before + bob_before

    def transfer(i):
        key = f"burst-contention-{uuid.uuid4()}"
        if i % 2 == 0:
            return post_transfer(
                ALICE_TOKEN, alice_wallet, bob_wallet, amount, key
            )
        return post_transfer(
            BOB_TOKEN, bob_wallet, alice_wallet, amount, key
        )

    responses = concurrent_calls(M, transfer)

    require(all(status == 200 for status, _ in responses),
            f"Some contention requests failed: {responses}")
    require(all(body.get("status") == "SUCCESS" for _, body in responses),
            f"Some contention transfers were not SUCCESS: {responses}")

    alice_after = wallet_balance(alice_wallet, ALICE_TOKEN)
    bob_after = wallet_balance(bob_wallet, BOB_TOKEN)
    total_after = alice_after + bob_after

    require(total_after == total_before,
            f"Conservation failed: {total_before} -> {total_after}")
    require(alice_after >= 0 and bob_after >= 0,
            f"Negative balance detected: Alice={alice_after}, Bob={bob_after}")

    print(
        f"[PASS] conservation under contention: {M} concurrent transfers, "
        f"total {total_before} -> {total_after}, no negative balance"
    )


def main():
    print(f"Target: {BASE_URL}")

    health_status, health = http_json("GET", "/actuator/health")
    require(health_status == 200 and health.get("status") == "UP",
            f"Service is not healthy: HTTP {health_status}: {health}")

    test_concurrent_get_or_create()
    alice_wallet, bob_wallet = ensure_transfer_wallets()
    test_idempotent_retry_storm(alice_wallet, bob_wallet)
    test_conservation_under_contention(alice_wallet, bob_wallet)

    print("\nALL BURST CHECKS PASSED")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, KeyError) as error:
        print(f"\n[FAIL] {error}", file=sys.stderr)
        sys.exit(1)
