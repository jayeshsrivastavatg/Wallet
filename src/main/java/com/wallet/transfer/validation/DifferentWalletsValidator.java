package com.wallet.transfer.validation;

import com.wallet.transfer.TransferRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DifferentWalletsValidator
        implements ConstraintValidator<DifferentWallets, TransferRequest> {

    @Override
    public boolean isValid(TransferRequest request, ConstraintValidatorContext context) {
        if (request.getFrom() == null || request.getTo() == null) {
            // Individual @NotNull constraints handle this case.
            return true;
        }
        return !request.getFrom().equals(request.getTo());
    }
}
