package com.wallet.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID walletId) {
        super("Wallet not found: " + walletId);
    }
}
