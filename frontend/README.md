# Wallet & P2P Transfer – Frontend

A minimal React + Vite developer UI for the Wallet & P2P Transfer Spring Boot service.
Its primary purpose is to demonstrate and manually exercise the backend while making
idempotency behavior visible.

---

## Prerequisites

- **Node.js ≥ 20.19.0** or **≥ 22.12.0** (required by Vite 8)

---

## Install

```bash
cd frontend
npm install
```

## Run (development)

```bash
npm run dev
```

The UI starts at **http://localhost:5173**.

---

## Backend

The Spring Boot application must be running on **port 8080** before you open the UI.

The Vite dev server proxies these paths directly to `http://localhost:8080`:

| Path | Backend endpoint |
|---|---|
| `/wallets` | Wallet API |
| `/transfers` | Transfer API |
| `/actuator` | Spring Actuator |

All API calls in the React code use **relative URLs** (e.g. `fetch('/wallets')`), so no
hardcoded host is needed and no CORS configuration is required for local development.

---

## Usage walkthrough

1. Enter a bearer token (must match a `bearer_token` value in the `users` table).
2. Click **Create / Load My Wallet** — calls `POST /wallets`.
3. Fill in a destination Wallet ID and an amount in paise, then click **Send Transfer**.
4. The idempotency key is shown in the UI and in the Debug panel.

### Demonstrating idempotency with "Retry Same Request"

After clicking **Send Transfer** once, click **Retry Same Request**.

This button deliberately reuses the **exact same request body**, including the same
`idempotency_key`, without generating a new UUID.

Expected outcomes:

| Retry scenario | Expected HTTP status | Expected result |
|---|---|---|
| Same key, same body (SUCCESS) | 200 | Same transfer ID returned, balances unchanged |
| Same key, same body (DECLINED) | 200 | Same DECLINED transfer returned |
| Same key, **different** body | 409 | Conflict error shown prominently |

The Debug panel at the bottom always shows the last request body, response status, and
response body, making the idempotency flow easy to follow.
