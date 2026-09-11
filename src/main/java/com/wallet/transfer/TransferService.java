package com.wallet.transfer;

import com.wallet.wallet.Wallet;
import com.wallet.wallet.WalletAccessDeniedException;
import com.wallet.wallet.WalletNotFoundException;
import com.wallet.wallet.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository   walletRepository;

    public TransferService(TransferRepository transferRepository,
                           WalletRepository walletRepository) {
        this.transferRepository = transferRepository;
        this.walletRepository   = walletRepository;
    }

    /**
     * Executes a transfer atomically within a single transaction.
     *
     * <p>Lock ordering: always acquire the wallet with the lexicographically
     * smaller UUID first, regardless of transfer direction. This prevents
     * deadlocks when concurrent transfers touch the same pair of wallets.
     */
    @Transactional
    public TransferResponse executeTransfer(UUID fromWalletId,
                                            UUID toWalletId,
                                            long amountPaise,
                                            String idempotencyKey,
                                            UUID authenticatedUserId) {
        if (fromWalletId.equals(toWalletId)) {
            throw new IllegalArgumentException(
                    "Source and destination wallet must be different");
        }

        // ── 1. Deterministic lock order ──────────────────────────────────────
        boolean fromFirst = fromWalletId.compareTo(toWalletId) < 0;
        UUID firstId  = fromFirst ? fromWalletId : toWalletId;
        UUID secondId = fromFirst ? toWalletId   : fromWalletId;

        Wallet first  = lockOrThrow(firstId);
        Wallet second = lockOrThrow(secondId);

        Wallet fromWallet = fromFirst ? first  : second;
        Wallet toWallet   = fromFirst ? second : first;

        // ── 2. Ownership check ───────────────────────────────────────────────
        if (!fromWallet.getUserId().equals(authenticatedUserId)) {
            throw new WalletAccessDeniedException();
        }

        // ── 3. Transfer logic ────────────────────────────────────────────────
        Instant now = Instant.now();
        TransferStatus status;

        if (fromWallet.getBalancePaise() < amountPaise) {
            // Insufficient balance — record DECLINED, touch no balances.
            status = TransferStatus.DECLINED;
        } else {
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - amountPaise);
            toWallet.setBalancePaise(toWallet.getBalancePaise()   + amountPaise);
            fromWallet.setUpdatedAt(now);
            toWallet.setUpdatedAt(now);
            status = TransferStatus.SUCCESS;
        }

        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                fromWalletId,
                toWalletId,
                amountPaise,
                idempotencyKey,
                status,
                now,
                now
        );

        transferRepository.save(transfer);
        return new TransferResponse(transfer.getTransferId(), transfer.getStatus());
    }

    /**
     * Returns a transfer by ID, allowing only the owner of the source wallet to view it.
     */
    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId, UUID authenticatedUserId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        Wallet fromWallet = walletRepository.findById(transfer.getFromWalletId())
                .orElseThrow(() -> new WalletNotFoundException(transfer.getFromWalletId()));

        if (!fromWallet.getUserId().equals(authenticatedUserId)) {
            throw new WalletAccessDeniedException();
        }

        return new TransferResponse(transfer.getTransferId(), transfer.getStatus());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Wallet lockOrThrow(UUID walletId) {
        return walletRepository.findByIdWithPessimisticWriteLock(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
