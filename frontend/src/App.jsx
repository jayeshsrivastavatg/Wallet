import { useEffect, useState } from 'react'
import { apiFetch } from './api.js'

const USERS = {
  alice: { name: 'Alice', token: 'burst-alice-token' },
  bob: { name: 'Bob', token: 'burst-bob-token' },
}

function parseRupeesToPaise(value) {
  const trimmed = value.trim()
  if (!/^\d+(\.\d{0,2})?$/.test(trimmed)) return null
  const [rupees, paise = ''] = trimmed.split('.')
  return Number(rupees) * 100 + Number((paise + '00').slice(0, 2))
}

function formatPaise(paise) {
  return `₹${(paise / 100).toFixed(2)}`
}

export default function App() {
  const [activeUser, setActiveUser] = useState('alice')
  const [wallets, setWallets] = useState({})
  const [amount, setAmount] = useState('')
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [message, setMessage] = useState(null)

  const recipientUser = activeUser === 'alice' ? 'bob' : 'alice'
  const currentWallet = wallets[activeUser]
  const recipientWallet = wallets[recipientUser]

  async function loadWallets() {
    setLoading(true)
    const entries = await Promise.all(
      Object.entries(USERS).map(async ([key, user]) => {
        const result = await apiFetch('/wallets', {
          method: 'POST',
          token: user.token,
        })
        return [key, result]
      }),
    )

    const next = {}
    const failed = entries.find(([, result]) => !result.ok)

    for (const [key, result] of entries) {
      if (result.ok) next[key] = result.data
    }

    setWallets(next)
    setLoading(false)

    if (failed) {
      setMessage({
        type: 'error',
        text: 'Demo accounts are not ready yet. Please refresh in a moment.',
      })
    }
  }

  useEffect(() => {
    loadWallets()
  }, [])

  async function sendMoney() {
    const amountPaise = parseRupeesToPaise(amount)

    if (!amountPaise || !currentWallet || !recipientWallet) {
      setMessage({ type: 'error', text: 'Enter a valid amount greater than ₹0.' })
      return
    }

    setSending(true)
    setMessage(null)

    const result = await apiFetch('/transfers', {
      method: 'POST',
      token: USERS[activeUser].token,
      body: {
        from: currentWallet.walletId,
        to: recipientWallet.walletId,
        amount_paise: amountPaise,
        idempotency_key: crypto.randomUUID(),
      },
    })

    setSending(false)

    if (!result.ok) {
      const text = result.status === 409
        ? 'This transfer was already submitted with different details.'
        : `Transfer failed (${result.status ?? 'network error'}).`
      setMessage({ type: 'error', text })
      return
    }

    if (result.data?.status === 'DECLINED') {
      setMessage({ type: 'error', text: 'Transfer declined: insufficient balance.' })
    } else {
      setMessage({
        type: 'success',
        text: `${formatPaise(amountPaise)} sent to ${USERS[recipientUser].name}.`,
      })
      setAmount('')
    }

    await loadWallets()
  }

  return (
    <main className="page">
      <section className="shell">
        <header className="header">
          <div>
            <div className="brand">Wallet</div>
            <p className="subtitle">Simple P2P transfer demo</p>
          </div>
          <button className="refresh" onClick={loadWallets} disabled={loading}>
            Refresh
          </button>
        </header>

        <div className="user-switch">
          {Object.entries(USERS).map(([key, user]) => (
            <button
              key={key}
              className={activeUser === key ? 'user-pill active' : 'user-pill'}
              onClick={() => {
                setActiveUser(key)
                setMessage(null)
              }}
            >
              {user.name}
            </button>
          ))}
        </div>

        <section className="balance-card">
          <span className="eyebrow">{USERS[activeUser].name}'s balance</span>
          <strong className="balance">
            {loading || !currentWallet ? '—' : formatPaise(currentWallet.balancePaise)}
          </strong>
          {currentWallet && (
            <span className="wallet-id">
              Wallet {currentWallet.walletId.slice(0, 8)}…
            </span>
          )}
        </section>

        <section className="send-card">
          <h2>Send money</h2>

          <label className="field-label" htmlFor="amount">Amount</label>
          <div className="amount-field">
            <span>₹</span>
            <input
              id="amount"
              inputMode="decimal"
              value={amount}
              onChange={event => setAmount(event.target.value)}
              placeholder="0.00"
              aria-label="Amount in rupees"
            />
          </div>

          <div className="recipient">
            <span>Send to</span>
            <strong>{USERS[recipientUser].name}</strong>
          </div>

          <button
            className="send-button"
            onClick={sendMoney}
            disabled={sending || loading || !currentWallet || !recipientWallet || !amount}
          >
            {sending ? 'Sending…' : `Send to ${USERS[recipientUser].name}`}
          </button>

          {message && (
            <div className={`message ${message.type}`}>
              {message.text}
            </div>
          )}
        </section>

        <p className="footnote">
          Demo accounts use bearer-token authentication automatically in the background.
        </p>
      </section>
    </main>
  )
}
