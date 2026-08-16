package com.smile.aceeconomy.domain;

import java.util.Optional;

/**
 * Typed outcome of a v2 economy use case. A {@link Success} may carry a non-fatal
 * {@code auditError}: when audit recording fails after the balance mutation has already
 * committed, the error is surfaced here rather than silently swallowed.
 */
public sealed interface EconomyResult<T> permits EconomyResult.Success, EconomyResult.Failure {

    static <T> Success<T> success(T value) {
        return new Success<>(value, null);
    }

    static <T> Success<T> success(T value, Throwable auditError) {
        return new Success<>(value, auditError);
    }

    static <T> Failure<T> failure(EconomyError error, String message) {
        return new Failure<>(error, message);
    }

    boolean isSuccess();

    boolean isFailure();

    EconomyError error();

    String message();

    /** Non-fatal audit recording failure, surfaced rather than swallowed (empty unless a Success carried one). */
    default Optional<Throwable> auditFailure() {
        return Optional.empty();
    }

    T value();

    record Success<T>(T value, Throwable auditError) implements EconomyResult<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public EconomyError error() {
            return null;
        }

        @Override
        public String message() {
            return null;
        }

        @Override
        public T value() {
            return value;
        }

        /** Non-fatal audit recording failure, surfaced rather than swallowed. */
        public Optional<Throwable> auditFailure() {
            return Optional.ofNullable(auditError);
        }
    }

    record Failure<T>(EconomyError error, String message) implements EconomyResult<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T value() {
            return null;
        }
    }
}
