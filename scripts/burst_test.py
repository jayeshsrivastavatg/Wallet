#!/usr/bin/env python3
"""
One-command live concurrency probe for the Wallet service.

Usage (normal / evaluator mode – no secrets needed):
    python3 scripts/burst_test.py
    python3 scripts/burst_test.py https://wallet-ihcp.onrender.com

Optional (private fresh-user mode – guarantees a genuine creation race):
    BURST_DATABASE_URL="postgresql://user:pass@host/db?sslmode=require" \\
    python3 scripts/burst_test.py https://wallet-ihcp.onrender.com

The deployed demo database must be seeded once with scripts/seed_burst.sql
for Alice and Bob (Gates 2 & 3).
Only Python's standard library is used; psql is required when BURST_DATABASE_URL
is set.
"""

import json
import os
import shutil
import subprocess
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlparse
from urllib.request import Request, urlopen

BASE_URL = (sys.argv[1] if len(sys.argv) > 1
            else os.getenv("WALLET_BASE_URL", "https://wallet-ihcp.onrender.com")).rstrip("/")

ALICE_TOKEN = os.getenv("BURST_ALICE_TOKEN", "burst-alice-token")
BOB_TOKEN = os.getenv("BURST_BOB_TOKEN", "burst-bob-token")
NEW_USER_TOKEN = os.getenv("BURST_NEW_USER_TOKEN", "burst-new-user-token")

# Read BURST_DATABASE_URL but NEVER print or log it.
_DATABASE_URL = os.getenv("BURST_DATABASE_URL", "")

N = int(os.getenv("BURST_WALLET_REQUESTS", "50"))
K = int(os.getenv("BURST_RETRY_REQUESTS", "30"))
M = int(os.getenv("BURST_TRANSFER_REQUESTS", "200"))
TIMEOUT = int(os.getenv("BURST_TIMEOUT_SECONDS", "60"))


# ── HTTP helpers ───────────────────────────────────────────────────────────────

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
    with ThreadPoolExecutor(max_workers=min(count, 50)) as executor:
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


# ── Database helper – credentials via libpq env vars, not argv ────────────────

def _build_pg_env(database_url: str) -> dict:
    """
    Parse a PostgreSQL URL and return an env dict with libpq variables.
    The password is placed in PGPASSWORD (never passed on the command line).
    """
    parsed = urlparse(database_url)

    if (
        parsed.scheme not in ("postgres", "postgresql")
        or not parsed.hostname
        or not parsed.username
        or not parsed.path.lstrip("/")
    ):
        raise RuntimeError("BURST_DATABASE_URL is not a valid PostgreSQL connection URL.")

    env = os.environ.copy()
    env.pop("BURST_DATABASE_URL", None)
    if parsed.hostname:
        env["PGHOST"] = parsed.hostname
    if parsed.port:
        env["PGPORT"] = str(parsed.port)
    if parsed.path and parsed.path.lstrip("/"):
        env["PGDATABASE"] = unquote(parsed.path.lstrip("/"))
    if parsed.username:
        env["PGUSER"] = unquote(parsed.username)
    if parsed.password:
        env["PGPASSWORD"] = unquote(parsed.password)  # never printed

    # Honour sslmode if present in query string.
    query_params = parse_qs(parsed.query or "")
    if query_params.get("sslmode"):
        env["PGSSLMODE"] = query_params["sslmode"][0]
    else:
        # Default to require for managed cloud databases.
        env.setdefault("PGSSLMODE", "require")

    return env


def _psql_run(sql: str, pg_env: dict, *, label: str = "") -> None:
    """
    Run a single SQL statement via psql.
    Credentials come from pg_env (libpq vars), not from argv.
    Raises RuntimeError on failure without leaking credentials.
    """
    result = subprocess.run(
        [
            "psql",
            "-v", "ON_ERROR_STOP=1",
            "-X",   # skip .psqlrc
            "-q",   # quiet
            "-c", sql,
        ],
        env=pg_env,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        # stderr is deliberately not included – it can contain host/user info.
        raise RuntimeError(
            f"PostgreSQL setup failed ({label}). "
            "Check database connectivity and credentials."
        )


def _db_insert_user(user_id: str, token: str, pg_env: dict) -> None:
    """Insert only the user row. NO wallet is created here."""
    sql = (
        "INSERT INTO users (user_id, user_name, bearer_token, created_at) "
        f"VALUES ('{user_id}', 'Burst Race User', '{token}', CURRENT_TIMESTAMP);"
    )
    _psql_run(sql, pg_env, label="insert_user")


def _db_delete_user(user_id: str, pg_env: dict) -> None:
    """
    Delete ONLY the temporary user created by this run.
    Scoped by the exact UUID; Alice/Bob are never touched.
    """
    _psql_run(
        f"DELETE FROM wallets WHERE user_id = '{user_id}';",
        pg_env, label="cleanup_wallet",
    )
    _psql_run(
        f"DELETE FROM users WHERE user_id = '{user_id}';",
        pg_env, label="cleanup_user",
    )


# ── Gate 1: concurrent wallet creation ────────────────────────────────────────

def test_concurrent_get_or_create():
    """
    Gate 1 – concurrent wallet creation.

    MODE 1 (no BURST_DATABASE_URL):
        Uses the persistent demo token.  Prints honest warnings that the wallet
        may already exist.  Still verifies 50 concurrent calls -> 1 walletId.

    MODE 2 (BURST_DATABASE_URL set):
        Creates a brand-new user with NO wallet immediately before the burst.
        This guarantees a genuine first-time creation race.
        Cleans up the temporary user in a finally block.
    """
    use_db = bool(_DATABASE_URL)

    if not use_db:
        # ── MODE 1: normal / evaluator ────────────────────────────────────────
        print("[WARN] Using persistent demo wallet-test user.")
        print("[WARN] If this user already owns a wallet, this run verifies concurrent")
        print("[WARN] get-or-return, not the initial wallet-creation race.")
        print("[WARN] Use BURST_DATABASE_URL mode for a guaranteed fresh creation race.")

        responses = concurrent_calls(N, lambda _: post_wallet(NEW_USER_TOKEN))
        require(all(status == 200 for status, _ in responses),
                f"Not all wallet requests succeeded: {responses}")
        wallet_ids = {body["walletId"] for _, body in responses}
        require(len(wallet_ids) == 1,
                f"Expected exactly one wallet, got {len(wallet_ids)}: {wallet_ids}")
        print(f"[PASS] concurrent get-or-create: {N} requests -> 1 wallet")
        return next(iter(wallet_ids))

    # ── MODE 2: fresh-user / private ─────────────────────────────────────────
    if not shutil.which("psql"):
        raise RuntimeError(
            "BURST_DATABASE_URL is set but psql is not installed. "
            "Install postgresql-client and retry."
        )

    pg_env = _build_pg_env(_DATABASE_URL)
    fresh_user_id = str(uuid.uuid4())
    fresh_token = f"burst-race-{uuid.uuid4()}"

    _db_insert_user(fresh_user_id, fresh_token, pg_env)
    print("[INFO] Created temporary authenticated user with no wallet")

    try:
        responses = concurrent_calls(N, lambda _: post_wallet(fresh_token))
        require(all(status == 200 for status, _ in responses),
                f"Not all wallet requests succeeded: {responses}")
        wallet_ids = {body["walletId"] for _, body in responses}
        require(len(wallet_ids) == 1,
                f"Expected exactly one wallet, got {len(wallet_ids)}: {wallet_ids}")
        print(f"[PASS] fresh concurrent wallet creation: {N} requests -> 1 wallet")
        return next(iter(wallet_ids))

    finally:
        try:
            _db_delete_user(fresh_user_id, pg_env)
        except RuntimeError as cleanup_err:
            print(f"[WARN] Cleanup of temporary race user failed: {cleanup_err}",
                  file=sys.stderr)


# ── Gate 2: idempotent retry storm ────────────────────────────────────────────

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


# ── Gate 3: conservation under contention ─────────────────────────────────────

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


# ── Entry point ───────────────────────────────────────────────────────────────

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
