package com.wallet.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    /**
     * Owning user — stored as a plain UUID to keep the entity simple.
     * The FK constraint lives in the Flyway migration.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Constructors ────────────────────────────────────────────────────────

    protected Wallet() {
    }

    public Wallet(UUID walletId, UUID userId, long balancePaise,
                  Instant createdAt, Instant updatedAt) {
        this.walletId = walletId;
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public UUID getWalletId() {
        return walletId;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ── Setters ─────────────────────────────────────────────────────────────

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public void setBalancePaise(long balancePaise) {
        this.balancePaise = balancePaise;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
