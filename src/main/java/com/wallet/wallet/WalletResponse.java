package com.wallet.wallet;

import java.util.UUID;

public class WalletResponse {

    private final UUID walletId;
    private final long balancePaise;

    public WalletResponse(UUID walletId, long balancePaise) {
        this.walletId = walletId;
        this.balancePaise = balancePaise;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }
}
