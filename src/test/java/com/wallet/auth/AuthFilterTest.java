package com.wallet.auth;

import com.wallet.user.User;
import com.wallet.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthFilterTest {

    @Mock
    private UserRepository userRepository;

    private AuthFilter authFilter;

    private static final String VALID_TOKEN = "valid-token-abc";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authFilter = new AuthFilter(userRepository);
    }

    // ── 1. Valid Bearer token → request continues ────────────────────────────

    @Test
    void validToken_setsAttributeAndContinuesChain() throws Exception {
        User user = new User(USER_ID, "alice", VALID_TOKEN, null, Instant.now());
        when(userRepository.findByBearerToken(VALID_TOKEN)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(AuthFilter.ATTR_AUTHENTICATED_USER_ID)).isEqualTo(USER_ID);
        assertThat(chain.getRequest()).isNotNull(); // chain was invoked
    }

    // ── 2. Missing Authorization header → 401 ───────────────────────────────

    @Test
    void missingHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // chain was NOT invoked
        verifyNoInteractions(userRepository);
    }

    // ── 3. Malformed Authorization header (no "Bearer " prefix) → 401 ────────

    @Test
    void malformedHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz"); // wrong scheme
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(userRepository);
    }

    // ── 4. Unknown token → 401 ───────────────────────────────────────────────

    @Test
    void unknownToken_returns401() throws Exception {
        when(userRepository.findByBearerToken("unknown-token")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer unknown-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verify(userRepository).findByBearerToken("unknown-token");
    }

    // ── 5. /actuator/health bypasses authentication ──────────────────────────

    @Test
    void actuatorHealth_isExcludedFromFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        boolean shouldSkip = authFilter.shouldNotFilter(request);

        assertThat(shouldSkip).isTrue();
        verifyNoInteractions(userRepository);
    }

    // ── 6. Other paths are NOT excluded ─────────────────────────────────────

    @Test
    void otherPaths_areNotExcluded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets");

        boolean shouldSkip = authFilter.shouldNotFilter(request);

        assertThat(shouldSkip).isFalse();
    }

    // ── 7. Empty token after "Bearer " prefix → 401, repo never called ────────

    @Test
    void emptyTokenAfterBearerPrefix_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer ");          // blank token
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(userRepository);
    }
}

