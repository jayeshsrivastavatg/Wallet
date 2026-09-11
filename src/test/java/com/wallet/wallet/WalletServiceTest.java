package com.wallet.wallet;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    private WalletService walletService;

    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository);
    }

    // ── getOrCreateWallet ────────────────────────────────────────────────────

    @Test
    void getOrCreateWallet_insertsAndReturnsWallet() {
        Wallet wallet = wallet(WALLET_ID, USER_ID, 0L);
        doNothing().when(walletRepository).insertIfAbsent(any(UUID.class), eq(USER_ID));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getOrCreateWallet(USER_ID);

        // insertIfAbsent was called with the correct userId
        ArgumentCaptor<UUID> walletIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(walletRepository).insertIfAbsent(walletIdCaptor.capture(), eq(USER_ID));
        assertThat(walletIdCaptor.getValue()).isNotNull();

        assertThat(response.getWalletId()).isEqualTo(WALLET_ID);
        assertThat(response.getBalancePaise()).isZero();
    }

    @Test
    void getOrCreateWallet_throwsWhenWalletMissingAfterUpsert() {
        doNothing().when(walletRepository).insertIfAbsent(any(UUID.class), eq(USER_ID));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getOrCreateWallet(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wallet not found after upsert");
    }

    // ── getWallet ────────────────────────────────────────────────────────────

    @Test
    void getWallet_returnsWalletForOwner() {
        Wallet wallet = wallet(WALLET_ID, USER_ID, 500L);
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWallet(WALLET_ID, USER_ID);

        assertThat(response.getWalletId()).isEqualTo(WALLET_ID);
        assertThat(response.getBalancePaise()).isEqualTo(500L);
    }

    @Test
    void getWallet_throwsNotFoundForUnknownId() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(WALLET_ID, USER_ID))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void getWallet_throwsAccessDeniedForWrongUser() {
        UUID otherUserId = UUID.randomUUID();
        Wallet wallet = wallet(WALLET_ID, otherUserId, 0L);
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.getWallet(WALLET_ID, USER_ID))
                .isInstanceOf(WalletAccessDeniedException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Wallet wallet(UUID walletId, UUID userId, long balancePaise) {
        return new Wallet(walletId, userId, balancePaise, Instant.now(), Instant.now());
    }
}
