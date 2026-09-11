package com.wallet.transfer;

import com.wallet.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /transfers
     * Executes (or records as DECLINED) a transfer between two wallets.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = authenticatedUserId(httpRequest);
        TransferResponse response = transferService.executeTransfer(
                request.getFrom(),
                request.getTo(),
                request.getAmountPaise(),
                request.getIdempotencyKey(),
                userId
        );
        return ResponseEntity.ok(response);
    }

    /**
     * GET /transfers/{id}
     * Returns the transfer record for its source-wallet owner.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        UUID userId = authenticatedUserId(httpRequest);
        TransferResponse response = transferService.getTransfer(id, userId);
        return ResponseEntity.ok(response);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID authenticatedUserId(HttpServletRequest request) {
        return (UUID) request.getAttribute(AuthFilter.ATTR_AUTHENTICATED_USER_ID);
    }
}
