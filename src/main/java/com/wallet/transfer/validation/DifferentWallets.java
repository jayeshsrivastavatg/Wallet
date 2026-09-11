package com.wallet.transfer.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a {@code TransferRequest} when {@code from} and {@code to} are the same UUID.
 */
@Documented
@Constraint(validatedBy = DifferentWalletsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DifferentWallets {

    String message() default "from and to wallet must be different";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
