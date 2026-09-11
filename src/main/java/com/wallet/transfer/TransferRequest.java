package com.wallet.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wallet.transfer.validation.DifferentWallets;

import java.util.UUID;

@DifferentWallets
public class TransferRequest {

    @NotNull(message = "from wallet ID must not be null")
    @JsonProperty("from")
    private UUID from;

    @NotNull(message = "to wallet ID must not be null")
    @JsonProperty("to")
    private UUID to;

    @Positive(message = "amount_paise must be greater than zero")
    @JsonProperty("amount_paise")
    private long amountPaise;

    @NotBlank(message = "idempotency_key must not be blank")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    // ── Getters & Setters ────────────────────────────────────────────────────

    public UUID getFrom() {
        return from;
    }

    public void setFrom(UUID from) {
        this.from = from;
    }

    public UUID getTo() {
        return to;
    }

    public void setTo(UUID to) {
        this.to = to;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
