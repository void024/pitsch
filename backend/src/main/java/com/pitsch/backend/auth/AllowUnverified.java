package com.pitsch.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Write endpoint that a user whose email is not yet verified may call. When verification is required, unverified users
 * may read but every other write is refused with EMAIL_NOT_VERIFIED.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowUnverified {
}
