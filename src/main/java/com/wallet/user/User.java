package com.wallet.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "bearer_token", nullable = false, unique = true)
    private String bearerToken;

    @Column(name = "other_details")
    private String otherDetails;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    // ── Constructors ────────────────────────────────────────────────────────

    protected User() {
    }

    public User(UUID userId, String userName, String bearerToken,
                String otherDetails, Instant createdAt) {
        this.userId = userId;
        this.userName = userName;
        this.bearerToken = bearerToken;
        this.otherDetails = otherDetails;
        this.createdAt = createdAt;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public UUID getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public String getOtherDetails() {
        return otherDetails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // ── Setters ─────────────────────────────────────────────────────────────

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public void setOtherDetails(String otherDetails) {
        this.otherDetails = otherDetails;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
