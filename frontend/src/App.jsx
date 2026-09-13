import { useState } from 'react'
import { apiFetch } from './api.js'

// ── Small helper to pretty-print JSON ─────────────────────────────────────────
function pretty(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'string') return v
  return JSON.stringify(v, null, 2)
}

// ── Status-coloured badge ─────────────────────────────────────────────────────
function StatusBadge({ status }) {
  if (!status) return null
  return <span className={`status-${status}`}>{status}</span>
}

// ── Error/notice banner ───────────────────────────────────────────────────────
function ApiError({ result }) {
  if (!result) return null
  if (result.ok) return null
  const is409 = result.status === 409
  return (
    <div className={`error${is409 ? ' conflict' : ''}`}>
      {is409
        ? <>⚠️ <strong>HTTP 409 — Idempotency Conflict.</strong> Same key used with different parameters.</>
        : <>Error {result.status ?? 'network'}: {pretty(result.data)}</>}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
export default function App() {
  // ── Auth ────────────────────────────────────────────────────────────────────
  const [token, setToken] = useState('')

  // ── Wallet state ─────────────────────────────────────────────────────────────
  const [wallet, setWallet] = useState(null)      // { walletId, balancePaise }
  const [walletErr, setWalletErr] = useState(null)

  // ── Transfer form ─────────────────────────────────────────────────────────────
  const [toWallet, setToWallet] = useState('')
  const [amount, setAmount] = useState('')

  // ── Transfer result ───────────────────────────────────────────────────────────
  const [transferResult, setTransferResult] = useState(null)  // last POST result
  const [lastTransferRequest, setLastTransferRequest] = useState(null)

  // ── Check-transfer section ────────────────────────────────────────────────────
  const [checkId, setCheckId] = useState('')
  const [checkResult, setCheckResult] = useState(null)

  // ── Debug panel ───────────────────────────────────────────────────────────────
  const [debug, setDebug] = useState(null)

  // ── Helpers ───────────────────────────────────────────────────────────────────
  function record(method, path, reqBody, result) {
    setDebug({ method, path, reqBody, status: result.status, resBody: result.data })
  }

  async function loadWallet() {
    setWalletErr(null)
    const res = await apiFetch('/wallets', { method: 'POST', token })
    record('POST', '/wallets', null, res)
    if (res.ok) {
      setWallet(res.data)
    } else {
      setWalletErr(res)
    }
  }

  async function refreshWallet() {
    if (!wallet) return
    setWalletErr(null)
    const path = `/wallets/${wallet.walletId}`
    const res = await apiFetch(path, { token })
    record('GET', path, null, res)
    if (res.ok) {
      setWallet(res.data)
    } else {
      setWalletErr(res)
    }
  }

  async function sendTransfer(requestBody) {
    const path = '/transfers'
    const res = await apiFetch(path, { method: 'POST', token, body: requestBody })
    record('POST', path, requestBody, res)
    setTransferResult({ ...res, idempotencyKey: requestBody.idempotency_key })
    if (res.ok) {
      // Refresh wallet balance after a successful transfer request
      await refreshWallet()
    }
  }

  async function handleSendTransfer() {
    const parsedAmount = Number(amount)
    if (!Number.isInteger(parsedAmount) || parsedAmount <= 0) {
      return
    }
    const requestBody = {
      from: wallet.walletId,
      to: toWallet,
      amount_paise: parsedAmount,
      idempotency_key: crypto.randomUUID(),
    }
    setLastTransferRequest(requestBody)
    await sendTransfer(requestBody)
  }

  async function handleRetry() {
    if (!lastTransferRequest) return
    await sendTransfer(lastTransferRequest)
  }

  async function checkTransfer() {
    const path = `/transfers/${checkId}`
    const res = await apiFetch(path, { token })
    record('GET', path, null, res)
    setCheckResult(res)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  return (
    <div className="app">
      <h1>Wallet &amp; P2P Transfer Demo</h1>

      {/* ── Auth ────────────────────────────────────────────────────────────── */}
      <div className="card">
        <h2>Authentication</h2>
        <div className="row">
          <label htmlFor="token">Bearer Token</label>
          <input
            id="token"
            type="text"
            value={token}
            onChange={e => setToken(e.target.value)}
            placeholder="paste your bearer token here"
          />
        </div>
      </div>

      {/* ── Wallet ──────────────────────────────────────────────────────────── */}
      <div className="card">
        <h2>Wallet</h2>
        <div className="btn-row">
          <button className="btn-primary" onClick={loadWallet} disabled={!token}>
            Create / Load My Wallet
          </button>
          <button className="btn-secondary" onClick={refreshWallet} disabled={!wallet}>
            Refresh Wallet
          </button>
        </div>

        {wallet && (
          <div style={{ marginTop: 12 }}>
            <div className="info-row">
              <span className="key">Wallet ID</span>
              <span className="value">{wallet.walletId}</span>
            </div>
            <div className="info-row">
              <span className="key">Balance (paise)</span>
              <span className="value">{wallet.balancePaise}</span>
            </div>
          </div>
        )}

        {walletErr && <ApiError result={walletErr} />}
      </div>

      {/* ── Transfer ────────────────────────────────────────────────────────── */}
      {wallet && (
        <div className="card">
          <h2>Send Transfer</h2>

          <div className="row">
            <label>From (your wallet)</label>
            <input type="text" value={wallet.walletId} readOnly />
          </div>
          <div className="row">
            <label htmlFor="toWallet">Destination Wallet ID</label>
            <input
              id="toWallet"
              type="text"
              value={toWallet}
              onChange={e => setToWallet(e.target.value)}
              placeholder="UUID of destination wallet"
            />
          </div>
          <div className="row">
            <label htmlFor="amount">Amount (paise)</label>
            <input
              id="amount"
              type="number"
              min="1"
              step="1"
              value={amount}
              onChange={e => setAmount(e.target.value)}
              placeholder="e.g. 1000"
            />
          </div>

          <div className="btn-row">
            <button
              className="btn-primary"
              onClick={handleSendTransfer}
              disabled={!toWallet || !Number.isInteger(Number(amount)) || Number(amount) <= 0}
            >
              Send Transfer
            </button>
            <button
              className="btn-warn"
              onClick={handleRetry}
              disabled={!lastTransferRequest}
              title="Resends the EXACT same request including the same idempotency key"
            >
              Retry Same Request
            </button>
          </div>

          {transferResult && (
            <div style={{ marginTop: 12 }}>
              <div className="info-row">
                <span className="key">Idempotency Key</span>
                <span className="value">{transferResult.idempotencyKey}</span>
              </div>
              {transferResult.ok ? (
                <>
                  <div className="info-row">
                    <span className="key">Transfer ID</span>
                    <span className="value">{transferResult.data?.transfer_id}</span>
                  </div>
                  <div className="info-row">
                    <span className="key">Status</span>
                    <span className="value">
                      <StatusBadge status={transferResult.data?.status} />
                    </span>
                  </div>
                  <div className="info-row">
                    <span className="key">HTTP Status</span>
                    <span className="value">{transferResult.status}</span>
                  </div>
                </>
              ) : (
                <ApiError result={transferResult} />
              )}
            </div>
          )}
        </div>
      )}

      {/* ── Check Transfer ──────────────────────────────────────────────────── */}
      <div className="card">
        <h2>Check Transfer</h2>
        <div className="row">
          <label htmlFor="checkId">Transfer ID</label>
          <input
            id="checkId"
            type="text"
            value={checkId}
            onChange={e => setCheckId(e.target.value)}
            placeholder="paste a transfer UUID"
          />
        </div>
        <div className="btn-row">
          <button className="btn-secondary" onClick={checkTransfer} disabled={!checkId || !token}>
            Check Status
          </button>
        </div>
        {checkResult && checkResult.ok && (
          <div style={{ marginTop: 10 }}>
            <div className="info-row">
              <span className="key">Transfer ID</span>
              <span className="value">{checkResult.data?.transfer_id}</span>
            </div>
            <div className="info-row">
              <span className="key">Status</span>
              <span className="value">
                <StatusBadge status={checkResult.data?.status} />
              </span>
            </div>
          </div>
        )}
        {checkResult && !checkResult.ok && <ApiError result={checkResult} />}
      </div>

      {/* ── Debug Panel ─────────────────────────────────────────────────────── */}
      {debug && (
        <div className="debug">
          <h2>Debug</h2>

          <div className="debug-row">
            <div className="debug-label">Request</div>
            <pre>
              <span className="http-method">{debug.method}</span>{' '}{debug.path}
            </pre>
          </div>

          {debug.reqBody && (
            <div className="debug-row">
              <div className="debug-label">Request Body</div>
              <pre>{pretty(debug.reqBody)}</pre>
            </div>
          )}

          {lastTransferRequest?.idempotency_key && (
            <div className="debug-row">
              <div className="debug-label">Current Idempotency Key</div>
              <pre>{lastTransferRequest.idempotency_key}</pre>
            </div>
          )}

          <div className="debug-row">
            <div className="debug-label">Response Status</div>
            <pre>
              <span className={debug.status && debug.status < 400 ? 'http-ok' : 'http-err'}>
                {debug.status ?? 'network error'}
              </span>
            </pre>
          </div>

          <div className="debug-row">
            <div className="debug-label">Response Body</div>
            <pre>{pretty(debug.resBody)}</pre>
          </div>
        </div>
      )}
    </div>
  )
}
