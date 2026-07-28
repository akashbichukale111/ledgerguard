package dev.ledgerguard.transaction.adapter.in.web;

import java.net.URI;
import java.time.Clock;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.ledgerguard.common.core.error.DomainException;
import dev.ledgerguard.common.core.error.ErrorCode;
import dev.ledgerguard.common.core.money.CurrencyMismatchException;
import dev.ledgerguard.transaction.application.IdempotencyConflictException;

/**
 * Maps every failure to an RFC 9457 Problem Details response.
 *
 * <p>Two rules hold without exception:
 *
 * <ul>
 *   <li><b>No internals leak.</b> No stack traces, no class names, no SQL fragments. An attacker
 *       learns nothing about the implementation from an error response.
 *   <li><b>Every response carries a correlation ID and a stable {@code errorCode}.</b> The
 *       correlation ID is what lets a user report a failure that an engineer can actually find; the
 *       error code is what lets a client branch without string-matching a message.
 * </ul>
 */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);
    private static final String TYPE_BASE = "https://ledgerguard.dev/problems/";

    private final Clock clock;

    public ProblemDetailsHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(TransactionController.MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingKey(
            TransactionController.MissingIdempotencyKeyException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_REQUIRED, e.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, e.errorCode(), e.getMessage(), request);
        if (e.errorCode() == ErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT) {
            // Tell the caller to come back rather than leaving them to guess.
            detail.setProperty("retryAfterSeconds", 1);
        }
        return detail;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.CURRENCY_MISMATCH, e.getMessage(), request);
    }

    @ExceptionHandler(ArithmeticException.class)
    public ProblemDetail handlePrecision(ArithmeticException e, HttpServletRequest request) {
        // Money.of throws this when an amount carries more precision than the currency permits.
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.AMOUNT_PRECISION_EXCEEDED, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e, HttpServletRequest request) {
        // Retries are exhausted by the time this surfaces. 409 tells the client the request was
        // well-formed but lost a race, so retrying is reasonable.
        return problem(
                HttpStatus.CONFLICT,
                ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently; retry the request",
                request);
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException e, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.errorCode(), e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String correlationId = correlationId(request);
        // Log the full detail server-side; return none of it. This is the only place the two
        // diverge, and deliberately so.
        log.error("unhandled exception correlationId={}", correlationId, e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the correlation ID when reporting this.",
                request);
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.description());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", code.name());
        problem.setProperty("correlationId", correlationId(request));
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    private String correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return header != null && !header.isBlank() ? header : "unassigned";
    }
}
