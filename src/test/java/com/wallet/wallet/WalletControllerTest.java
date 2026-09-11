package com.wallet.wallet;

import com.wallet.user.User;
import com.wallet.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WalletService walletService;

    /** Required by AuthFilter which is picked up in the web slice. */
    @MockBean
    private UserRepository userRepository;

    private static final UUID   USER_ID   = UUID.randomUUID();
    private static final UUID   WALLET_ID = UUID.randomUUID();
    private static final String TOKEN     = "test-bearer-token";

    @BeforeEach
    void setUp() {
        User user = new User(USER_ID, "alice", TOKEN, null, Instant.now());
        when(userRepository.findByBearerToken(TOKEN)).thenReturn(Optional.of(user));
    }

    // ── POST /wallets ────────────────────────────────────────────────────────

    @Test
    void postWallets_returnsWalletResponse() throws Exception {
        when(walletService.getOrCreateWallet(USER_ID))
                .thenReturn(new WalletResponse(WALLET_ID, 0L));

        mockMvc.perform(post("/wallets")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(WALLET_ID.toString()))
                .andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void postWallets_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/wallets"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /wallets/{id} ────────────────────────────────────────────────────

    @Test
    void getWallet_returnsWalletForOwner() throws Exception {
        when(walletService.getWallet(WALLET_ID, USER_ID))
                .thenReturn(new WalletResponse(WALLET_ID, 1500L));

        mockMvc.perform(get("/wallets/{id}", WALLET_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(WALLET_ID.toString()))
                .andExpect(jsonPath("$.balancePaise").value(1500));
    }

    @Test
    void getWallet_returns404WhenNotFound() throws Exception {
        when(walletService.getWallet(eq(WALLET_ID), any(UUID.class)))
                .thenThrow(new WalletNotFoundException(WALLET_ID));

        mockMvc.perform(get("/wallets/{id}", WALLET_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void getWallet_returns403ForWrongOwner() throws Exception {
        when(walletService.getWallet(eq(WALLET_ID), any(UUID.class)))
                .thenThrow(new WalletAccessDeniedException());

        mockMvc.perform(get("/wallets/{id}", WALLET_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void getWallet_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/wallets/{id}", WALLET_ID))
                .andExpect(status().isUnauthorized());
    }
}
