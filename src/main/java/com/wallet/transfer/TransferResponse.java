package com.wallet.transfer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class TransferResponse {

    @JsonProperty("transfer_id")
    private final UUID transferId;

    @JsonProperty("status")
    private final TransferStatus status;

    public TransferResponse(UUID transferId, TransferStatus status) {
        this.transferId = transferId;
        this.status = status;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public TransferStatus getStatus() {
        return status;
    }
}
