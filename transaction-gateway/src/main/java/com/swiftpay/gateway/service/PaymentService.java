package com.swiftpay.gateway.service;

import com.swiftpay.gateway.client.LedgerClient;
import com.swiftpay.gateway.dto.AccountDto;
import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.dto.PaymentResponse;
import com.swiftpay.gateway.event.PaymentInitiatedEvent;
import com.swiftpay.gateway.exception.DuplicateTransactionException;
import com.swiftpay.gateway.exception.InsufficientFundsException;
import com.swiftpay.gateway.model.Payment;
import com.swiftpay.gateway.model.PaymentStatus;
import com.swiftpay.gateway.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service orchestrating the initialization of P2P payments.
 * Handles idempotency checks, external balance queries, local database persistence, 
 * and event dispatching in a transactional context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final LedgerClient ledgerClient;

    /**
     * Initiates a payment transaction.
     * Performs a distributed Redis lock for idempotency, verifies uniqueness in the database,
     * queries the Ledger Service to check the sender's balance, registers the payment as PENDING,
     * and publishes a transactional event to trigger asynchronous Kafka processing.
     *
     * @param request the payment details from the REST controller
     * @return the accepted transaction response
     * @throws DuplicateTransactionException if the transaction ID was already processed
     * @throws InsufficientFundsException if the sender does not have enough balance
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Processing payment request for transaction: {}", request.getTransactionId());

        // Step 1: Distributed idempotency check using Redis SET NX.
        // The lock is held for 24 hours. If it cannot be acquired, reject the request immediately.
        if (!idempotencyService.lock(request.getTransactionId())) {
            log.warn("Idempotency lock check failed for transaction: {}", request.getTransactionId());
            throw new DuplicateTransactionException("Transaction ID already processed: " + request.getTransactionId());
        }

        try {
            // Step 2: Backup safety check against database record.
            // Guards against Redis eviction or key loss scenarios.
            if (paymentRepository.existsById(request.getTransactionId())) {
                log.warn("Transaction already exists in database: {}", request.getTransactionId());
                throw new DuplicateTransactionException("Transaction ID already exists in DB: " + request.getTransactionId());
            }

            // Step 3: Fetch sender account details from the Ledger Service and check balance.
            AccountDto account = ledgerClient.getAccount(request.getSenderId());
            if (account.getBalance().compareTo(request.getAmount()) < 0) {
                log.warn("Insufficient funds for sender {}. Balance: {}, Required: {}", 
                        request.getSenderId(), account.getBalance(), request.getAmount());
                throw new InsufficientFundsException("Insufficient funds. Available: " + account.getBalance());
            }

            // Step 4: Persist the payment record as PENDING.
            Payment payment = Payment.builder()
                    .id(request.getTransactionId())
                    .senderId(request.getSenderId())
                    .receiverId(request.getReceiverId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status(PaymentStatus.PENDING)
                    .build();

            paymentRepository.save(payment);
            log.info("Saved payment {} with status PENDING", payment.getId());

            // Step 5: Publish a Spring Application Event.
            // A transactional listener publishes this event to Kafka AFTER the database transaction commits.
            PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                    .transactionId(payment.getId())
                    .senderId(payment.getSenderId())
                    .receiverId(payment.getReceiverId())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .build();

            eventPublisher.publishEvent(event);
            log.info("Published PaymentInitiatedEvent for transaction {}", payment.getId());

            // Note: The Redis idempotency lock is NOT released on success to enforce the 24-hour window.
            return PaymentResponse.builder()
                    .transactionId(payment.getId())
                    .status("ACCEPTED")
                    .message("Payment transaction accepted and is being processed.")
                    .build();

        } catch (Exception ex) {
            log.error("Failed to process transaction: {}", request.getTransactionId(), ex);
            // Release the Redis lock on failure so the client can retry the request.
            idempotencyService.unlock(request.getTransactionId());
            throw ex;
        }
    }

    /**
     * Updates the status of an existing payment record.
     * This is invoked asynchronously when consuming the Kafka completion/failure results from the ledger.
     *
     * @param transactionId the unique identifier of the payment
     * @param status the target status (SUCCESS or FAILED)
     * @param failureReason details on why the transaction failed, if applicable
     */
    @Transactional
    public void updatePaymentStatus(UUID transactionId, PaymentStatus status, String failureReason) {
        log.info("Updating status for transaction {} to {} with reason: {}", transactionId, status, failureReason);
        paymentRepository.findById(transactionId).ifPresentOrElse(
                payment -> {
                    payment.setStatus(status);
                    payment.setFailureReason(failureReason);
                    paymentRepository.save(payment);
                    log.info("Successfully updated status of transaction {}", transactionId);
                },
                () -> log.error("Transaction {} not found to update status", transactionId)
        );
    }
}
