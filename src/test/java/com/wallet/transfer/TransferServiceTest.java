package com.wallet.transfer;

import com.wallet.wallet.Wallet;
import com.wallet.wallet.WalletAccessDeniedException;
import com.wallet.wallet.WalletNotFoundException;
import com.wallet.wallet.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferRepository transferRepository;
    @Mock private WalletRepository   walletRepository;

    private TransferService transferService;
    private SimpleMeterRegistry meterRegistry;

    // Fixed UUIDs — FROM_WALLET < TO_WALLET in compareTo ordering.
    private static final UUID USER_A_ID   = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_B_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FROM_WALLET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TO_WALLET   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final String IDEM_KEY  = "key-123";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        transferService = new TransferService(
                transferRepository, walletRepository, meterRegistry);
    }

    // ── Successful transfer (new idempotency key) ────────────────────────────

    @Test
    void successfulTransfer_debitsSourceAndCreditsDestination() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 1000L);
        stubFastPathMiss();
        stubLocks(from, to);
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(1);

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(from.getBalancePaise()).isEqualTo(3000L);
        assertThat(to.getBalancePaise()).isEqualTo(3000L);
        verify(transferRepository).updateStatus(
                eq(resp.getTransferId()), eq(TransferStatus.SUCCESS), any(Instant.class));
    }

    @Test
    void successfulTransfer_exactAmount_dropsSourceToZero() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 2000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubFastPathMiss();
        stubLocks(from, to);
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(1);

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(from.getBalancePaise()).isZero();
        assertThat(to.getBalancePaise()).isEqualTo(2000L);
    }

    // ── Insufficient balance → DECLINED ─────────────────────────────────────

    @Test
    void insufficientBalance_recordsDECLINEDAndLeavesBalancesUnchanged() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 500L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 1000L);
        stubFastPathMiss();
        stubLocks(from, to);
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(1);

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getStatus()).isEqualTo(TransferStatus.DECLINED);
        assertThat(from.getBalancePaise()).isEqualTo(500L);  // unchanged
        assertThat(to.getBalancePaise()).isEqualTo(1000L);   // unchanged
        verify(transferRepository).updateStatus(
                eq(resp.getTransferId()), eq(TransferStatus.DECLINED), any(Instant.class));
    }

    // ── Idempotency: same key + same params → replay ─────────────────────────

    @Test
    void sameKeyAndParams_returnsExistingTransferWithoutModifyingBalances() {
        UUID existingId = UUID.randomUUID();
        Transfer existing = transfer(existingId, FROM_WALLET, TO_WALLET,
                                     2000L, IDEM_KEY, TransferStatus.SUCCESS);
        Wallet fromWallet = wallet(FROM_WALLET, USER_A_ID, 3000L);

        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.of(existing));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(fromWallet));

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getTransferId()).isEqualTo(existingId);
        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        // No wallet locking and no new insert should happen.
        verify(walletRepository, never()).findByIdWithPessimisticWriteLock(any());
        verify(transferRepository, never()).insertPendingIfAbsent(any(), any(), any(), any(Long.class), any());
        // Balances unchanged (no setters called — verified implicitly by never stubs above).
    }

    @Test
    void sameKeyAndParams_declinedTransfer_returnsSameDeclinedStatus() {
        UUID existingId = UUID.randomUUID();
        Transfer existing = transfer(existingId, FROM_WALLET, TO_WALLET,
                                     9999L, IDEM_KEY, TransferStatus.DECLINED);
        Wallet fromWallet = wallet(FROM_WALLET, USER_A_ID, 500L);

        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.of(existing));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(fromWallet));

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 9999L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getTransferId()).isEqualTo(existingId);
        assertThat(resp.getStatus()).isEqualTo(TransferStatus.DECLINED);
    }

    // ── Idempotency: same key + different params → 409 ───────────────────────

    @Test
    void sameKeyDifferentAmount_throwsIdempotencyConflict() {
        Transfer existing = transfer(UUID.randomUUID(), FROM_WALLET, TO_WALLET,
                                     1000L, IDEM_KEY, TransferStatus.SUCCESS);
        Wallet fromWallet = wallet(FROM_WALLET, USER_A_ID, 5000L);

        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.of(existing));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(fromWallet));

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID))   // different amount
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void sameKeyDifferentSourceWallet_throwsIdempotencyConflict() {
        UUID otherFrom = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
        Transfer existing = transfer(UUID.randomUUID(), otherFrom, TO_WALLET,
                                     1000L, IDEM_KEY, TransferStatus.SUCCESS);
        Wallet fromWallet = wallet(otherFrom, USER_A_ID, 5000L);

        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.of(existing));
        when(walletRepository.findById(otherFrom))
                .thenReturn(Optional.of(fromWallet));

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))   // different from
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void sameKeyDifferentDestinationWallet_throwsIdempotencyConflict() {
        UUID otherTo = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
        Transfer existing = transfer(UUID.randomUUID(), FROM_WALLET, otherTo,
                                     1000L, IDEM_KEY, TransferStatus.SUCCESS);
        Wallet fromWallet = wallet(FROM_WALLET, USER_A_ID, 5000L);

        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.of(existing));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(fromWallet));

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))   // different to
                .isInstanceOf(IdempotencyConflictException.class);
    }

    // ── Idempotency: insert race — losing thread returns winner's record ──────

    @Test
    void insertReturnsZero_losingThread_returnsWinnerTransferWithoutModifyingBalances() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);

        // Fast-path miss (key not committed yet when fast-path runs).
        stubFastPathMiss();
        // Both wallets are locked before the insert attempt; the loser discovers
        // the conflict only after insertPendingIfAbsent returns 0.
        stubLocks(from, to);

        // The atomic insert loses the race (returns 0).
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(0);

        // The winner's committed record is now visible.
        UUID winnerId = UUID.randomUUID();
        Transfer winner = transfer(winnerId, FROM_WALLET, TO_WALLET,
                                   2000L, IDEM_KEY, TransferStatus.SUCCESS);
        // insertPendingIfAbsent returning 0 causes findByIdempotencyKey to be called again.
        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.empty())   // first call (fast-path)
                .thenReturn(Optional.of(winner)); // second call (post-insert)
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(from));

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID);

        // Must return the winner's transfer without any balance mutation.
        assertThat(resp.getTransferId()).isEqualTo(winnerId);
        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(from.getBalancePaise()).isEqualTo(5000L);  // unchanged
        assertThat(to.getBalancePaise()).isEqualTo(0L);       // unchanged
        verify(transferRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void insertReturnsZero_raceLostWithDifferentParams_throwsConflict() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        // Both wallets are locked before the insert attempt; the loser discovers
        // the conflict only after insertPendingIfAbsent returns 0.
        stubLocks(from, wallet(TO_WALLET, USER_B_ID, 0L));
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(0);

        UUID winnerId = UUID.randomUUID();
        Transfer winner = transfer(winnerId, FROM_WALLET, TO_WALLET,
                                   9999L, IDEM_KEY, TransferStatus.SUCCESS); // different amount
        // fast-path: empty → winner becomes visible after insert returns 0.
        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(from));

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    // ── Only the insert-winning request may modify balances ───────────────────

    @Test
    void onlyInsertWinner_modifiesWalletBalances() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubFastPathMiss();
        stubLocks(from, to);
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(1);

        transferService.executeTransfer(FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID);

        assertThat(from.getBalancePaise()).isEqualTo(4000L);
        assertThat(to.getBalancePaise()).isEqualTo(1000L);
    }

    @Test
    void updateStatusReturnsZero_throwsIllegalStateException() {
        // Verifies that if updateStatus finalises 0 rows the service throws
        // IllegalStateException. Because executeTransfer() is @Transactional,
        // that exception triggers a rollback in production. Rollback behavior
        // is not asserted here — it will be covered by a real PostgreSQL
        // integration test in a later phase.
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubFastPathMiss();
        stubLocks(from, to);
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);  // this thread won the insert race
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(0);  // but finalisation fails (0 rows updated)

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1 updated row");
    }

    // ── Wrong source-wallet owner → 403 ─────────────────────────────────────

    @Test
    void wrongOwner_throwsAccessDenied() {
        UUID intruder = UUID.randomUUID();
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubFastPathMiss();
        stubLocks(from, to);

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, intruder))
                .isInstanceOf(WalletAccessDeniedException.class);

        verify(transferRepository, never()).insertPendingIfAbsent(any(), any(), any(), any(Long.class), any());
        verify(transferRepository, never()).save(any());
    }

    // ── Wallet not found ─────────────────────────────────────────────────────

    @Test
    void sourceWalletNotFound_throwsWalletNotFoundException() {
        // FROM_WALLET < TO_WALLET → FROM_WALLET locked first → throws immediately.
        stubFastPathMiss();
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void destinationWalletNotFound_throwsWalletNotFoundException() {
        stubFastPathMiss();
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.of(wallet(FROM_WALLET, USER_A_ID, 5000L)));
        when(walletRepository.findByIdWithPessimisticWriteLock(TO_WALLET))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // ── Same source and destination ──────────────────────────────────────────

    @Test
    void sameSourceAndDestination_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, FROM_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");

        verify(walletRepository, never()).findByIdWithPessimisticWriteLock(any());
        verify(transferRepository, never()).save(any());
    }

    // ── Deterministic lock ordering ──────────────────────────────────────────

    @Test
    void lockOrder_isAlwaysLowerUUIDFirst_regardlessOfDirection() {
        // Reversed direction: TO_WALLET → FROM_WALLET.
        // FROM_WALLET < TO_WALLET, so FROM_WALLET must still be locked first.
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubFastPathMiss();
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.of(from));
        when(walletRepository.findByIdWithPessimisticWriteLock(TO_WALLET))
                .thenReturn(Optional.of(to));
        when(transferRepository.insertPendingIfAbsent(any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(transferRepository.updateStatus(any(UUID.class), any(TransferStatus.class), any(Instant.class)))
                .thenReturn(1);

        // TO_WALLET sends to FROM_WALLET (reversed direction), USER_B owns TO_WALLET.
        transferService.executeTransfer(TO_WALLET, FROM_WALLET, 100L, IDEM_KEY, USER_B_ID);

        ArgumentCaptor<UUID> lockOrder = ArgumentCaptor.forClass(UUID.class);
        verify(walletRepository, org.mockito.Mockito.times(2))
                .findByIdWithPessimisticWriteLock(lockOrder.capture());

        assertThat(lockOrder.getAllValues().get(0)).isEqualTo(FROM_WALLET);
        assertThat(lockOrder.getAllValues().get(1)).isEqualTo(TO_WALLET);
    }

    // ── getTransfer ──────────────────────────────────────────────────────────

    @Test
    void getTransfer_returnsResponse() {
        UUID tid = UUID.randomUUID();
        Transfer t = transfer(tid, FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, TransferStatus.SUCCESS);
        when(transferRepository.findById(tid)).thenReturn(Optional.of(t));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(wallet(FROM_WALLET, USER_A_ID, 0L)));

        TransferResponse resp = transferService.getTransfer(tid, USER_A_ID);

        assertThat(resp.getTransferId()).isEqualTo(tid);
        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void getTransfer_unknownId_throws404() {
        UUID tid = UUID.randomUUID();
        when(transferRepository.findById(tid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(tid, USER_A_ID))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void getTransfer_wrongUser_throws403() {
        UUID tid = UUID.randomUUID();
        Transfer t = transfer(tid, FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, TransferStatus.SUCCESS);
        when(transferRepository.findById(tid)).thenReturn(Optional.of(t));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(wallet(FROM_WALLET, USER_A_ID, 0L)));

        assertThatThrownBy(() -> transferService.getTransfer(tid, UUID.randomUUID()))
                .isInstanceOf(WalletAccessDeniedException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Simulate fast-path miss: idempotency key not yet in DB. */
    private void stubFastPathMiss() {
        when(transferRepository.findByIdempotencyKey(IDEM_KEY))
                .thenReturn(Optional.empty());
    }

    private void stubLocks(Wallet from, Wallet to) {
        when(walletRepository.findByIdWithPessimisticWriteLock(from.getWalletId()))
                .thenReturn(Optional.of(from));
        when(walletRepository.findByIdWithPessimisticWriteLock(to.getWalletId()))
                .thenReturn(Optional.of(to));
    }

    private Wallet wallet(UUID id, UUID userId, long balance) {
        return new Wallet(id, userId, balance, Instant.now(), Instant.now());
    }

    private Transfer transfer(UUID id, UUID from, UUID to,
                              long amount, String key, TransferStatus status) {
        return new Transfer(id, from, to, amount, key, status, Instant.now(), Instant.now());
    }
}
