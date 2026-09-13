package com.wallet;

import com.wallet.transfer.IdempotencyConflictException;
import com.wallet.transfer.TransferResponse;
import com.wallet.transfer.TransferService;
import com.wallet.transfer.TransferStatus;
import com.wallet.wallet.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PostgresIntegrationTest {

    private static final int N =
            Integer.getInteger("wallet.test.concurrentWalletRequests", 32);

    private static final int K =
            Integer.getInteger("wallet.test.concurrentTransferRetries", 32);

    private static final int M =
            Integer.getInteger("wallet.test.concurrentTransfers", 64);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Test
    @DisplayName("Verify PostgreSQL container is running, Spring context loads, and Flyway created core tables")
    void postgresqlContainerIsRunningAndCoreTablesExist() {
        assertThat(postgres.isRunning()).isTrue();

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(tables).contains("users", "wallets", "transfers");
    }

    @Test
    @DisplayName("Concurrent wallet creation creates exactly one wallet")
    void concurrentWalletCreationCreatesExactlyOneWallet() throws Exception {
        UUID userId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                userId,
                "Concurrent User",
                "wallet-test-token-" + UUID.randomUUID()
        );

        ExecutorService executor = Executors.newFixedThreadPool(N);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < N; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return walletService.getOrCreateWallet(userId).getWalletId();
                }));
            }

            startGate.countDown();

            Set<UUID> returnedWalletIds = new HashSet<>();
            for (Future<UUID> future : futures) {
                returnedWalletIds.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(returnedWalletIds).hasSize(1);

            Long walletCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wallets WHERE user_id = ?",
                    Long.class,
                    userId
            );
            assertThat(walletCount).isEqualTo(1L);

            UUID storedWalletId = jdbcTemplate.queryForObject(
                    "SELECT wallet_id FROM wallets WHERE user_id = ?",
                    UUID.class,
                    userId
            );
            assertThat(storedWalletId).isEqualTo(returnedWalletIds.iterator().next());
        } finally {
            startGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Concurrent idempotent retries apply the transfer exactly once")
    void concurrentIdempotentRetriesApplyExactlyOnce() throws Exception {
        UUID fromUserId = UUID.randomUUID();
        UUID toUserId = UUID.randomUUID();
        UUID fromWalletId = UUID.randomUUID();
        UUID toWalletId = UUID.randomUUID();

        long initialFromBalance = 100_000L;
        long initialToBalance = 10_000L;
        long amountPaise = 1_000L;
        String idempotencyKey = "transfer-test-key-" + UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                fromUserId,
                "Sender",
                "sender-test-token-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                toUserId,
                "Receiver",
                "receiver-test-token-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                fromWalletId,
                fromUserId,
                initialFromBalance
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                toWalletId,
                toUserId,
                initialToBalance
        );

        ExecutorService executor = Executors.newFixedThreadPool(K);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < K; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return transferService.executeTransfer(
                            fromWalletId,
                            toWalletId,
                            amountPaise,
                            idempotencyKey,
                            fromUserId
                    );
                }));
            }

            startGate.countDown();

            Set<UUID> transferIds = new HashSet<>();
            for (Future<TransferResponse> future : futures) {
                TransferResponse response = future.get(30, TimeUnit.SECONDS);
                transferIds.add(response.getTransferId());
                assertThat(response.getStatus()).isEqualTo(TransferStatus.SUCCESS);
            }

            assertThat(transferIds).hasSize(1);

            Long transferCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                    Long.class,
                    idempotencyKey
            );
            assertThat(transferCount).isEqualTo(1L);

            Long finalFromBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                    Long.class,
                    fromWalletId
            );
            Long finalToBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                    Long.class,
                    toWalletId
            );

            assertThat(finalFromBalance).isEqualTo(initialFromBalance - amountPaise);
            assertThat(finalToBalance).isEqualTo(initialToBalance + amountPaise);
            assertThat(finalFromBalance + finalToBalance)
                    .isEqualTo(initialFromBalance + initialToBalance);
        } finally {
            startGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Reusing an idempotency key with different transfer parameters is rejected")
    void sameIdempotencyKeyWithDifferentBodyIsRejected() {
        UUID fromUserId = UUID.randomUUID();
        UUID toUserId = UUID.randomUUID();
        UUID fromWalletId = UUID.randomUUID();
        UUID toWalletId = UUID.randomUUID();
        String idempotencyKey = "conflict-test-key-" + UUID.randomUUID();

        long initialFromBalance = 20_000L;
        long initialToBalance = 5_000L;
        long firstAmountPaise = 1_000L;

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                fromUserId,
                "Conflict Sender",
                "conflict-sender-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                toUserId,
                "Conflict Receiver",
                "conflict-receiver-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                fromWalletId,
                fromUserId,
                initialFromBalance
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                toWalletId,
                toUserId,
                initialToBalance
        );

        TransferResponse firstResponse = transferService.executeTransfer(
                fromWalletId,
                toWalletId,
                firstAmountPaise,
                idempotencyKey,
                fromUserId
        );
        assertThat(firstResponse.getStatus()).isEqualTo(TransferStatus.SUCCESS);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transferService.executeTransfer(
                        fromWalletId,
                        toWalletId,
                        firstAmountPaise + 1,
                        idempotencyKey,
                        fromUserId
                )
        ).isInstanceOf(IdempotencyConflictException.class);

        Long transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                Long.class,
                idempotencyKey
        );
        Long finalFromBalance = jdbcTemplate.queryForObject(
                "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                Long.class,
                fromWalletId
        );
        Long finalToBalance = jdbcTemplate.queryForObject(
                "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                Long.class,
                toWalletId
        );

        assertThat(transferCount).isEqualTo(1L);
        assertThat(finalFromBalance).isEqualTo(initialFromBalance - firstAmountPaise);
        assertThat(finalToBalance).isEqualTo(initialToBalance + firstAmountPaise);
    }

    @Test
    @DisplayName("Concurrent transfers preserve money and never overdraw a wallet")
    void concurrentTransfersPreserveMoneyAndPreventOverdraft() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID walletA = UUID.randomUUID();
        UUID walletB = UUID.randomUUID();
        String keyPrefix = "contention-" + UUID.randomUUID() + "-";

        long initialBalanceA = 50_000L;
        long initialBalanceB = 50_000L;
        long initialTotal = initialBalanceA + initialBalanceB;

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                userA,
                "User A",
                "contention-a-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO users (user_id, user_name, bearer_token, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                userB,
                "User B",
                "contention-b-" + UUID.randomUUID()
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                walletA,
                userA,
                initialBalanceA
        );

        jdbcTemplate.update("""
                INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                walletB,
                userB,
                initialBalanceB
        );

        ExecutorService executor = Executors.newFixedThreadPool(M);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < M; i++) {
                final int requestIndex = i;
                futures.add(executor.submit(() -> {
                    startGate.await();

                    boolean aToB = requestIndex % 2 == 0;
                    boolean forceOverdraft = requestIndex % 5 == 0;

                    UUID fromWallet = aToB ? walletA : walletB;
                    UUID toWallet = aToB ? walletB : walletA;
                    UUID authenticatedUser = aToB ? userA : userB;
                    long amountPaise = forceOverdraft ? 1_000_000L : 500L;

                    return transferService.executeTransfer(
                            fromWallet,
                            toWallet,
                            amountPaise,
                            keyPrefix + requestIndex,
                            authenticatedUser
                    );
                }));
            }

            startGate.countDown();

            int successCount = 0;
            int declinedCount = 0;

            for (Future<TransferResponse> future : futures) {
                TransferResponse response = future.get(45, TimeUnit.SECONDS);

                if (response.getStatus() == TransferStatus.SUCCESS) {
                    successCount++;
                } else if (response.getStatus() == TransferStatus.DECLINED) {
                    declinedCount++;
                } else {
                    throw new AssertionError("Unexpected transfer status: " + response.getStatus());
                }
            }

            Long finalBalanceA = jdbcTemplate.queryForObject(
                    "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                    Long.class,
                    walletA
            );
            Long finalBalanceB = jdbcTemplate.queryForObject(
                    "SELECT balance_paise FROM wallets WHERE wallet_id = ?",
                    Long.class,
                    walletB
            );

            Long pendingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE idempotency_key LIKE ? AND status = 'PENDING'",
                    Long.class,
                    keyPrefix + "%"
            );

            assertThat(successCount).isGreaterThan(0);
            assertThat(declinedCount).isGreaterThan(0);
            assertThat(finalBalanceA).isGreaterThanOrEqualTo(0L);
            assertThat(finalBalanceB).isGreaterThanOrEqualTo(0L);
            assertThat(finalBalanceA + finalBalanceB).isEqualTo(initialTotal);
            assertThat(pendingCount).isZero();
        } finally {
            startGate.countDown();
            executor.shutdownNow();
        }
    }
}
