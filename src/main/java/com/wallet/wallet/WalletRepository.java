package com.wallet.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Creates a wallet for {@code userId} if one does not yet exist.
     * {@code ON CONFLICT (user_id) DO NOTHING} prevents any check-then-insert race.
     */
    @Modifying
    @Query(value = """
            INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
            VALUES (:walletId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id) DO NOTHING
            """,
            nativeQuery = true)
    void insertIfAbsent(@Param("walletId") UUID walletId,
                        @Param("userId") UUID userId);

    /**
     * Loads a wallet with a PESSIMISTIC_WRITE (SELECT … FOR UPDATE) lock.
     * Used by transfer processing to prevent concurrent balance updates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.walletId = :walletId")
    Optional<Wallet> findByIdWithPessimisticWriteLock(@Param("walletId") UUID walletId);
}
