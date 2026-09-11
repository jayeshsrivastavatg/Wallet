package com.wallet.wallet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Returns the wallet for {@code userId}, creating one atomically if it does
     * not yet exist. The native INSERT … ON CONFLICT DO NOTHING guarantees
     * exactly-once creation without a check-then-insert race.
     */
    @Transactional
    public WalletResponse getOrCreateWallet(UUID userId) {
        walletRepository.insertIfAbsent(UUID.randomUUID(), userId);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet not found after upsert for user: " + userId));

        return toResponse(wallet);
    }

    /**
     * Returns the wallet identified by {@code walletId}.
     *
     * @throws WalletNotFoundException    if no wallet exists with that ID.
     * @throws WalletAccessDeniedException if the wallet belongs to a different user.
     */
    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID requestingUserId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (!wallet.getUserId().equals(requestingUserId)) {
            throw new WalletAccessDeniedException();
        }

        return toResponse(wallet);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(wallet.getWalletId(), wallet.getBalancePaise());
    }
}
