package com.swiftpay.gateway.exception;

import com.swiftpay.gateway.dto.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller advice class intercepting exceptions thrown from the REST layer.
 * Maps exceptions to standardized JSON structures and returns appropriate HTTP status codes.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles idempotency conflict exceptions.
     * Thrown when a duplicate transaction identifier is sent.
     *
     * @param ex the DuplicateTransactionException instance
     * @return HTTP 409 Conflict with details
     */
    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<PaymentResponse> handleDuplicateTransaction(DuplicateTransactionException ex) {
        log.warn("Duplicate transaction blocked: {}", ex.getMessage());
        PaymentResponse response = PaymentResponse.builder()
                .status("CONFLICT")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles scenarios where the sender's account has insufficient balance.
     *
     * @param ex the InsufficientFundsException instance
     * @return HTTP 400 Bad Request with details
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<PaymentResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("Payment rejected due to insufficient funds: {}", ex.getMessage());
        PaymentResponse response = PaymentResponse.builder()
                .status("BAD_REQUEST")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles scenarios where the sender account is not registered.
     *
     * @param ex the AccountNotFoundException instance
     * @return HTTP 400 Bad Request with details
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<PaymentResponse> handleAccountNotFound(AccountNotFoundException ex) {
        log.warn("Payment rejected because account was not found: {}", ex.getMessage());
        PaymentResponse response = PaymentResponse.builder()
                .status("BAD_REQUEST")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles validation errors on incoming payment request bodies (e.g. invalid currency, negative amount).
     *
     * @param ex the MethodArgumentNotValidException thrown by Spring validation
     * @return HTTP 400 Bad Request with a map of field-specific validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed for payment request: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * Handles unhandled runtime exceptions.
     * Returns a general HTTP 500 error payload, preventing internal technical logs from leaking to clients.
     *
     * @param ex the general Exception instance
     * @return HTTP 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<PaymentResponse> handleGeneralException(Exception ex) {
        log.error("Unexpected error occurred in gateway", ex);
        PaymentResponse response = PaymentResponse.builder()
                .status("ERROR")
                .message("An unexpected error occurred.")
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
