package com.wallet.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    /**
     * Source wallet — stored as a plain UUID.
     * FK constraint is enforced in the Flyway migration.
     */
    @Column(name = "from_wallet_id", nullable = false)
    private UUID fromWalletId;

    /**
     * Destination wallet — stored as a plain UUID.
     * FK constraint is enforced in the Flyway migration.
     */
    @Column(name = "to_wallet_id", nullable = false)
    private UUID toWalletId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TransferStatus status;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Constructors ────────────────────────────────────────────────────────

    protected Transfer() {
    }

    public Transfer(UUID transferId, UUID fromWalletId, UUID toWalletId,
                    long amountPaise, String idempotencyKey,
                    TransferStatus status, Instant createdAt, Instant updatedAt) {
        this.transferId = transferId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public UUID getToWalletId() {
        return toWalletId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ── Setters ─────────────────────────────────────────────────────────────

    public void setTransferId(UUID transferId) {
        this.transferId = transferId;
    }

    public void setFromWalletId(UUID fromWalletId) {
        this.fromWalletId = fromWalletId;
    }

    public void setToWalletId(UUID toWalletId) {
        this.toWalletId = toWalletId;
    }

    public void setAmountPaise(long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
