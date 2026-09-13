package com.wallet.transfer;

import com.wallet.wallet.Wallet;
import com.wallet.wallet.WalletAccessDeniedException;
import com.wallet.wallet.WalletNotFoundException;
import com.wallet.wallet.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
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
     * <p>Idempotency: a PENDING placeholder is inserted with
     * {@code ON CONFLICT (idempotency_key) DO NOTHING} before touching any
     * wallet balance. Exactly one concurrent request receives an insert count
     * of 1 and may proceed; all others fall back to the existing record.
     * The PENDING insert, balance changes, and final status update are all
     * committed or rolled back together.
     *
     * <p>Lock ordering: the wallet with the lexicographically smaller UUID is
     * always locked first, regardless of transfer direction, to prevent deadlocks.
     */
    @Transactional
    public TransferResponse executeTransfer(UUID fromWalletId,
                                            UUID toWalletId,
                                            long amountPaise,
                                            String idempotencyKey,
                                            UUID authenticatedUserId) {
        // ── 0. Same-wallet guard ─────────────────────────────────────────────
        if (fromWalletId.equals(toWalletId)) {
            throw new IllegalArgumentException(
                    "Source and destination wallet must be different");
        }

        // ── 1. Fast-path idempotency check ───────────────────────────────────
        // An optimisation only — correctness is enforced by the atomic insert below.
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), fromWalletId, toWalletId,
                                   amountPaise, idempotencyKey, authenticatedUserId);
        }

        // ── 2. Deterministic wallet lock order ───────────────────────────────
        boolean fromFirst = fromWalletId.compareTo(toWalletId) < 0;
        UUID firstId  = fromFirst ? fromWalletId : toWalletId;
        UUID secondId = fromFirst ? toWalletId   : fromWalletId;

        Wallet first  = lockOrThrow(firstId);
        Wallet second = lockOrThrow(secondId);

        Wallet fromWallet = fromFirst ? first  : second;
        Wallet toWallet   = fromFirst ? second : first;

        // ── 3. Ownership check ───────────────────────────────────────────────
        if (!fromWallet.getUserId().equals(authenticatedUserId)) {
            throw new WalletAccessDeniedException();
        }

        // ── 4. Atomic PENDING insert ─────────────────────────────────────────
        UUID newTransferId = UUID.randomUUID();
        int inserted = transferRepository.insertPendingIfAbsent(
                newTransferId, fromWalletId, toWalletId, amountPaise, idempotencyKey);

        if (inserted == 0) {
            // Another concurrent request claimed this key first.
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Insert returned 0 but existing transfer not found for key: "
                                    + idempotencyKey));
            return resolveExisting(winner, fromWalletId, toWalletId,
                                   amountPaise, idempotencyKey, authenticatedUserId);
        }

        // ── 5. Balance movement ──────────────────────────────────────────────
        Instant now = Instant.now();
        TransferStatus finalStatus;

        if (fromWallet.getBalancePaise() < amountPaise) {
            // Insufficient balance — leave balances unchanged, record DECLINED.
            finalStatus = TransferStatus.DECLINED;
        } else {
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - amountPaise);
            toWallet.setBalancePaise(toWallet.getBalancePaise()   + amountPaise);
            fromWallet.setUpdatedAt(now);
            toWallet.setUpdatedAt(now);
            finalStatus = TransferStatus.SUCCESS;
        }

        // ── 6. Finalise the PENDING transfer ─────────────────────────────────
        int updated = transferRepository.updateStatus(newTransferId, finalStatus, now);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to finalise transfer " + newTransferId
                            + ": expected 1 updated row but got " + updated);
        }

        return new TransferResponse(newTransferId, finalStatus);
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

    /**
     * Handles an existing Transfer found via idempotency key.
     * Returns it if the request parameters match; throws 409 otherwise.
     */
    private TransferResponse resolveExisting(Transfer existing,
                                             UUID fromWalletId,
                                             UUID toWalletId,
                                             long amountPaise,
                                             String idempotencyKey,
                                             UUID authenticatedUserId) {
        // Ownership: only the source-wallet owner may see/replay this transfer.
        Wallet fromWallet = walletRepository.findById(existing.getFromWalletId())
                .orElseThrow(() -> new WalletNotFoundException(existing.getFromWalletId()));
        if (!fromWallet.getUserId().equals(authenticatedUserId)) {
            throw new WalletAccessDeniedException();
        }

        boolean sameParams = existing.getFromWalletId().equals(fromWalletId)
                && existing.getToWalletId().equals(toWalletId)
                && existing.getAmountPaise() == amountPaise;

        if (!sameParams) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        return new TransferResponse(existing.getTransferId(), existing.getStatus());
    }

    private Wallet lockOrThrow(UUID walletId) {
        return walletRepository.findByIdWithPessimisticWriteLock(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
