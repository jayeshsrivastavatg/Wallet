package com.wallet.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.user.User;
import com.wallet.user.UserRepository;
import com.wallet.wallet.WalletAccessDeniedException;
import com.wallet.wallet.WalletNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TransferService  transferService;
    @MockBean private UserRepository   userRepository;   // required by AuthFilter

    private static final UUID   USER_ID     = UUID.randomUUID();
    private static final UUID   FROM_WALLET = UUID.randomUUID();
    private static final UUID   TO_WALLET   = UUID.randomUUID();
    private static final UUID   TRANSFER_ID = UUID.randomUUID();
    private static final String TOKEN       = "test-token";

    @BeforeEach
    void setUp() {
        User user = new User(USER_ID, "alice", TOKEN, null, Instant.now());
        when(userRepository.findByBearerToken(TOKEN)).thenReturn(Optional.of(user));
    }

    // ── POST /transfers ──────────────────────────────────────────────────────

    @Test
    void postTransfer_success_returns200() throws Exception {
        when(transferService.executeTransfer(
                eq(FROM_WALLET), eq(TO_WALLET), eq(1000L), eq("key-1"), eq(USER_ID)))
                .thenReturn(new TransferResponse(TRANSFER_ID, TransferStatus.SUCCESS));

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 1000L, "key-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer_id").value(TRANSFER_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void postTransfer_declined_returns200WithDECLINED() throws Exception {
        when(transferService.executeTransfer(any(), any(), any(Long.class), any(), any()))
                .thenReturn(new TransferResponse(TRANSFER_ID, TransferStatus.DECLINED));

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 999999L, "key-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    void postTransfer_fromEqualsTo_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, FROM_WALLET, 100L, "key-3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postTransfer_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 0L, "key-4")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postTransfer_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, -1L, "key-5")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postTransfer_blankIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postTransfer_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "key-6")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postTransfer_sourceWalletNotFound_returns404() throws Exception {
        when(transferService.executeTransfer(any(), any(), any(Long.class), any(), any()))
                .thenThrow(new WalletNotFoundException(FROM_WALLET));

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "key-7")))
                .andExpect(status().isNotFound());
    }

    @Test
    void postTransfer_destinationWalletNotFound_returns404() throws Exception {
        when(transferService.executeTransfer(any(), any(), any(Long.class), any(), any()))
                .thenThrow(new WalletNotFoundException(TO_WALLET));

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "key-8")))
                .andExpect(status().isNotFound());
    }

    @Test
    void postTransfer_wrongOwner_returns403() throws Exception {
        when(transferService.executeTransfer(any(), any(), any(Long.class), any(), any()))
                .thenThrow(new WalletAccessDeniedException());

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "key-9")))
                .andExpect(status().isForbidden());
    }

    // ── GET /transfers/{id} ──────────────────────────────────────────────────

    @Test
    void getTransfer_existing_returns200() throws Exception {
        when(transferService.getTransfer(TRANSFER_ID, USER_ID))
                .thenReturn(new TransferResponse(TRANSFER_ID, TransferStatus.SUCCESS));

        mockMvc.perform(get("/transfers/{id}", TRANSFER_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer_id").value(TRANSFER_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void getTransfer_unknownId_returns404() throws Exception {
        when(transferService.getTransfer(eq(TRANSFER_ID), any()))
                .thenThrow(new TransferNotFoundException(TRANSFER_ID));

        mockMvc.perform(get("/transfers/{id}", TRANSFER_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTransfer_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/transfers/{id}", TRANSFER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String validBody(UUID from, UUID to, long amount, String key) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "from", from,
                "to", to,
                "amount_paise", amount,
                "idempotency_key", key
        ));
    }

    // ── Idempotency conflict ──────────────────────────────────────────────────

    @Test
    void postTransfer_idempotencyConflict_returns409() throws Exception {
        when(transferService.executeTransfer(any(), any(), any(Long.class), any(), any()))
                .thenThrow(new IdempotencyConflictException("key-conflict"));

        mockMvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(FROM_WALLET, TO_WALLET, 100L, "key-conflict")))
                .andExpect(status().isConflict());
    }
}
