
HIGH LEVEL DESIGN

```mermaid
flowchart LR

    Client[Client / Test Script]

    subgraph App["Spring Boot Wallet Service"]
        Auth[Auth Filter]

        Wallet[Wallet Module]
        Transfer[Transfer Module]

        Auth --> Wallet
        Auth --> Transfer
    end

    DB[(PostgreSQL<br/>Transactions<br/>Row Locks<br/>Unique Constraints)]

    Client -->|HTTP / REST| Auth

    Wallet --> DB
    Transfer --> DB
```
---

Entities:
USER: user_id, user_name, other_details, created_at
WALLET: wallet_id, user_id, balance_paise, created_at, updated_at
TRANSFER: transfer_id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, status, created_at, updated_at
TRANSFER STATUS: PENDING, SUCCESS, DECLINED

---


LOW LEVEL DESIGN

```mermaid
flowchart TD

    Auth[Auth Filter]

    WC[WalletController]
    TC[TransferController]

    WS[WalletService]
    TS[TransferService]

    WR[WalletRepository]
    TR[TransferRepository]

    DB[(PostgreSQL)]

    Auth --> WC
    Auth --> TC

    WC --> WS
    TC --> TS

    WS --> WR

    TS --> WR
    TS --> TR

    WR --> DB
    TR --> DB
```

Entities:
Controllers: WalletController → createOrGetWallet(), getWallet(); 
             TransferController → createTransfer(), getTransfer()
Services: WalletService → wallet creation/read; 
          TransferService → idempotency, lock wallets, check balance, debit/credit, update status
Repositories: UserRepository, WalletRepository, TransferRepository → DB operations, findById(), findByUserId(), findByIdempotencyKey(), save()
Concurrency: PostgreSQL row locking (FOR UPDATE) + deterministic lock order + unique constraints for user_id and idempotency_key