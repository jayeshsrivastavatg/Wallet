package com.wallet.transfer;

import com.wallet.wallet.Wallet;
import com.wallet.wallet.WalletAccessDeniedException;
import com.wallet.wallet.WalletNotFoundException;
import com.wallet.wallet.WalletRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferRepository transferRepository;
    @Mock private WalletRepository   walletRepository;

    private TransferService transferService;

    // Fixed UUIDs — from < to in compareTo ordering so "from" is locked first.
    private static final UUID USER_A_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_B_ID    = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FROM_WALLET  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TO_WALLET    = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final String IDEM_KEY   = "key-123";

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, walletRepository);
    }

    // ── Successful transfer ──────────────────────────────────────────────────

    @Test
    void successfulTransfer_debitsSourceAndCreditsDestination() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 1000L);
        stubLocks(from, to);
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 2000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(from.getBalancePaise()).isEqualTo(3000L); // 5000 - 2000
        assertThat(to.getBalancePaise()).isEqualTo(3000L);   // 1000 + 2000
    }

    @Test
    void successfulTransfer_exactAmount_dropsSourceToZero() {
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 2000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubLocks(from, to);
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        stubLocks(from, to);
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse resp = transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID);

        assertThat(resp.getStatus()).isEqualTo(TransferStatus.DECLINED);
        assertThat(from.getBalancePaise()).isEqualTo(500L);  // unchanged
        assertThat(to.getBalancePaise()).isEqualTo(1000L);   // unchanged
    }

    // ── Wrong source-wallet owner → 403 ─────────────────────────────────────

    @Test
    void wrongOwner_throwsAccessDenied() {
        UUID intruder = UUID.randomUUID();
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);
        stubLocks(from, to);

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, intruder))
                .isInstanceOf(WalletAccessDeniedException.class);

        verify(transferRepository, never()).save(any());
    }

    // ── Source wallet not found ──────────────────────────────────────────────

    @Test
    void sourceWalletNotFound_throwsWalletNotFoundException() {
        // from < to, so FROM_WALLET is locked first and is missing — throws immediately.
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // ── Destination wallet not found ─────────────────────────────────────────

    @Test
    void destinationWalletNotFound_throwsWalletNotFoundException() {
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.of(wallet(FROM_WALLET, USER_A_ID, 5000L)));
        when(walletRepository.findByIdWithPessimisticWriteLock(TO_WALLET))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer(
                FROM_WALLET, TO_WALLET, 1000L, IDEM_KEY, USER_A_ID))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // ── Deterministic lock ordering ──────────────────────────────────────────

    @Test
    void lockOrder_isAlwaysLowerUUIDFirst_regardlessOfDirection() {
        // Reverse direction: TO_WALLET → FROM_WALLET
        // FROM_WALLET < TO_WALLET, so FROM_WALLET must still be locked first.
        Wallet from = wallet(FROM_WALLET, USER_A_ID, 5000L);
        Wallet to   = wallet(TO_WALLET,   USER_B_ID, 0L);

        // Stub in natural order so wrong ordering would cause test failure
        when(walletRepository.findByIdWithPessimisticWriteLock(FROM_WALLET))
                .thenReturn(Optional.of(from));
        when(walletRepository.findByIdWithPessimisticWriteLock(TO_WALLET))
                .thenReturn(Optional.of(to));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // TO_WALLET sends to FROM_WALLET (reversed direction)
        transferService.executeTransfer(
                TO_WALLET, FROM_WALLET, 100L, IDEM_KEY, USER_B_ID);

        // FROM_WALLET (the smaller) must have been locked first
        var lockOrder = ArgumentCaptor.forClass(UUID.class);
        verify(walletRepository, org.mockito.Mockito.times(2))
                .findByIdWithPessimisticWriteLock(lockOrder.capture());

        assertThat(lockOrder.getAllValues().get(0)).isEqualTo(FROM_WALLET);
        assertThat(lockOrder.getAllValues().get(1)).isEqualTo(TO_WALLET);
    }

    // ── getTransfer ──────────────────────────────────────────────────────────

    @Test
    void getTransfer_returnsResponse() {
        UUID tid = UUID.randomUUID();
        Transfer transfer = transfer(tid, FROM_WALLET, TO_WALLET, TransferStatus.SUCCESS);
        when(transferRepository.findById(tid)).thenReturn(Optional.of(transfer));
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
        Transfer transfer = transfer(tid, FROM_WALLET, TO_WALLET, TransferStatus.SUCCESS);
        when(transferRepository.findById(tid)).thenReturn(Optional.of(transfer));
        when(walletRepository.findById(FROM_WALLET))
                .thenReturn(Optional.of(wallet(FROM_WALLET, USER_A_ID, 0L)));

        assertThatThrownBy(() -> transferService.getTransfer(tid, UUID.randomUUID()))
                .isInstanceOf(WalletAccessDeniedException.class);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubLocks(Wallet from, Wallet to) {
        when(walletRepository.findByIdWithPessimisticWriteLock(from.getWalletId()))
                .thenReturn(Optional.of(from));
        when(walletRepository.findByIdWithPessimisticWriteLock(to.getWalletId()))
                .thenReturn(Optional.of(to));
    }

    private Wallet wallet(UUID id, UUID userId, long balance) {
        return new Wallet(id, userId, balance, Instant.now(), Instant.now());
    }

    private Transfer transfer(UUID id, UUID from, UUID to, TransferStatus status) {
        return new Transfer(id, from, to, 1000L, "k", status, Instant.now(), Instant.now());
    }
}
