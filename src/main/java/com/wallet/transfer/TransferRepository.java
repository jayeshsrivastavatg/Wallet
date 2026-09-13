package com.wallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Attempts to insert a PENDING transfer row for {@code idempotencyKey}.
     * {@code ON CONFLICT (idempotency_key) DO NOTHING} makes the insert atomic:
     * exactly one concurrent request receives a return value of {@code 1};
     * all others receive {@code 0} and must fall back to the existing record.
     *
     * @return 1 if this request claimed the key, 0 if another already owned it.
     */
    @Modifying
    @Query(value = """
            INSERT INTO transfers (
                transfer_id, from_wallet_id, to_wallet_id,
                amount_paise, idempotency_key, status,
                created_at, updated_at
            )
            VALUES (
                :transferId, :fromWalletId, :toWalletId,
                :amountPaise, :idempotencyKey, 'PENDING',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            nativeQuery = true)
    int insertPendingIfAbsent(@Param("transferId")     UUID   transferId,
                              @Param("fromWalletId")   UUID   fromWalletId,
                              @Param("toWalletId")     UUID   toWalletId,
                              @Param("amountPaise")    long   amountPaise,
                              @Param("idempotencyKey") String idempotencyKey);

    /**
     * Transitions a Transfer from PENDING to its final status.
     * Returns the number of rows updated (1 on success, 0 if the row was not
     * in PENDING state — e.g. a concurrent update already finalised it).
     */
    @Modifying
    @Query("""
            UPDATE Transfer t
            SET t.status    = :status,
                t.updatedAt = :updatedAt
            WHERE t.transferId = :transferId
              AND t.status = com.wallet.transfer.TransferStatus.PENDING
            """)
    int updateStatus(@Param("transferId") UUID           transferId,
                     @Param("status")     TransferStatus status,
                     @Param("updatedAt")  Instant        updatedAt);
}
