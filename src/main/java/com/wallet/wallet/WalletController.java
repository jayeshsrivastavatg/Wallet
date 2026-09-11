package com.wallet.wallet;

import com.wallet.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /wallets
     * Get or atomically create the calling user's wallet.
     * Returns wallet ID and balance (always 0 for a freshly created wallet).
     */
    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreateWallet(HttpServletRequest request) {
        UUID userId = authenticatedUserId(request);
        WalletResponse response = walletService.getOrCreateWallet(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /wallets/{id}
     * Returns the wallet if it exists and belongs to the calling user.
     * 404 if not found, 403 if owned by another user.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID id,
                                                    HttpServletRequest request) {
        UUID userId = authenticatedUserId(request);
        WalletResponse response = walletService.getWallet(id, userId);
        return ResponseEntity.ok(response);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID authenticatedUserId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AuthFilter.ATTR_AUTHENTICATED_USER_ID);
    }
}
