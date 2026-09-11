package com.wallet.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class WalletAccessDeniedException extends RuntimeException {

    public WalletAccessDeniedException() {
        super("Access denied: wallet belongs to another user");
    }
}
