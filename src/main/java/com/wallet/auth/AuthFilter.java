package com.wallet.auth;

import com.wallet.user.User;
import com.wallet.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Stateless bearer-token authentication filter.
 *
 * <p>For every request (except {@code /actuator/health}):
 * <ol>
 *   <li>Reads the {@code Authorization} header.</li>
 *   <li>Requires the value to match {@code Bearer <token>}.</li>
 *   <li>Resolves the token to a {@link User} via {@link UserRepository}.</li>
 *   <li>On success, stores the user's UUID as the request attribute
 *       {@value #ATTR_AUTHENTICATED_USER_ID} and continues the chain.</li>
 *   <li>On any failure, responds with HTTP 401 and stops the chain.</li>
 * </ol>
 *
 * <p>Does not use Spring Security.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTHENTICATED_USER_ID = "authenticatedUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;

    public AuthFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // ── Skip list ────────────────────────────────────────────────────────────

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/actuator/health".equals(request.getRequestURI());
    }

    // ── Core logic ───────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (token.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed Authorization header");
            return;
        }

        Optional<User> userOpt = userRepository.findByBearerToken(token);
        if (userOpt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
            return;
        }

        request.setAttribute(ATTR_AUTHENTICATED_USER_ID, userOpt.get().getUserId());
        filterChain.doFilter(request, response);
    }
}
