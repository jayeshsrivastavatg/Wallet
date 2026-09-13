# Wallet & P2P Transfer

Live API: **https://wallet-ihcp.onrender.com**

Spring Boot + PostgreSQL wallet service focused on correctness under concurrency.

## Run locally

```bash
docker compose up --build
```

Health:

```bash
curl http://localhost:8080/actuator/health
```

Metrics:

```bash
curl http://localhost:8080/actuator/prometheus
```

## One-command live burst

The deployed demo database is seeded with three demo bearer tokens. Run:

```bash
python3 scripts/burst_test.py
```

Or target another deployment:

```bash
python3 scripts/burst_test.py https://your-service.example.com
```

The script uses only Python's standard library and reproduces all three required probes:

1. concurrent `POST /wallets` calls for the same user and verifies one wallet id;
2. concurrent identical transfer retries and verifies one financial effect plus identical results;
3. simultaneous A→B and B→A transfers and verifies total balance conservation and no negative balance.

For a fresh demo reset, run `scripts/seed_burst.sql` against the managed PostgreSQL database before the burst.

## One-page design write-up

### Data model

`users` stores the bearer token and user id. `wallets` has one row per user (`UNIQUE(user_id)`) and stores `balance_paise` as `BIGINT` with `CHECK (balance_paise >= 0)`. `transfers` stores source wallet, destination wallet, integer amount, status, and a globally unique `idempotency_key`. Money is never represented as floating point.

### Conservation and no overdraft

A transfer runs inside one short database transaction. Both wallet rows are locked with PostgreSQL pessimistic row locks (`SELECT ... FOR UPDATE` semantics). The two wallet UUIDs are always locked in deterministic sorted order, regardless of transfer direction, so A→B and B→A acquire the same locks in the same order and avoid the common deadlock pattern.

After both rows are locked, the service checks the source balance. If funds are insufficient it records `DECLINED` and leaves both balances unchanged. Otherwise the debit and credit are applied inside the same transaction. This is the simplest mechanism here because PostgreSQL is already the source of truth. I rejected JVM locks because they fail with multiple application instances, and rejected distributed locks/Redis, serializable isolation, event sourcing, and a separate ledger because they add infrastructure or complexity that this small workload does not require.

### Idempotency

`transfers.idempotency_key` has a database `UNIQUE` constraint. A request first performs a fast lookup, but correctness comes from an atomic `INSERT ... ON CONFLICT DO NOTHING` inside the same transaction as the wallet mutations. Only the request that wins that insert may change balances. A same-key/same-body retry returns the stored transfer result without a second debit or credit. A same-key/different-body retry returns HTTP `409`.

### Consistency vs availability

For money movement I choose consistency over availability. If PostgreSQL cannot provide the transaction or row locks, the transfer should fail rather than accept a write whose financial effect is uncertain. This knowingly gives up some availability during database failure or lock contention in exchange for preserving conservation, no-overdraft, and exactly-once financial effects.

### Operability

The Docker image is multi-stage, runs as a non-root user, and has a `HEALTHCHECK`. `docker-compose.yml` starts the app and PostgreSQL with one command. Production is deployed on Render with managed PostgreSQL. Logs are JSON and include `X-Correlation-ID`; meaningful events include transfer-created, wallet-debited, wallet-credited, transfer-declined, and idempotent-replay. Prometheus metrics expose HTTP request counts/latency (including p99 and status/error labels) plus transfer-created, insufficient-funds-declined, and idempotent-replay counters at `/actuator/prometheus`.

### AI usage

I directed the overall implementation and reviewed the architectural choices, API behavior, concurrency guarantees, and final code. AI was used to accelerate code generation, test scaffolding, Docker/observability configuration, review, and documentation. AI also proposed implementation options such as deterministic row-lock ordering and Micrometer/Prometheus instrumentation; I reviewed those proposals and accepted or simplified them before integration.

### Cost

The deployed service and managed PostgreSQL use Render's free tiers for this exercise, so the deployment cost is **₹0**. The free service may sleep when inactive and the free database is intended for temporary evaluation/prototyping.
