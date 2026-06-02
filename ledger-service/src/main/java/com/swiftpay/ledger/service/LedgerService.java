package com.swiftpay.ledger.service;

import com.swiftpay.ledger.event.PaymentCompletedEvent;
import com.swiftpay.ledger.event.PaymentFailedEvent;
import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.model.Account;
import com.swiftpay.ledger.model.EntryType;
import com.swiftpay.ledger.model.LedgerEntry;
import com.swiftpay.ledger.repository.AccountRepository;
import com.swiftpay.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {
        log.info("Processing PaymentInitiatedEvent for transaction: {}", event.getTransactionId());

        // 1. Idempotency Check
        if (ledgerEntryRepository.existsByTransactionId(event.getTransactionId())) {
            log.warn("Transaction {} has already been processed by Ledger. Re-publishing PaymentCompletedEvent.", event.getTransactionId());
            publishPaymentCompleted(event.getTransactionId());
            return;
        }

        // 2. Deterministic Lock Ordering to Prevent Deadlocks
        UUID senderId = event.getSenderId();
        UUID receiverId = event.getReceiverId();
        
        if (senderId.equals(receiverId)) {
            log.error("Sender and receiver are the same account: {}", senderId);
            publishPaymentFailed(event.getTransactionId(), "Sender and receiver cannot be the same account");
            return;
        }

        UUID firstId = senderId.compareTo(receiverId) < 0 ? senderId : receiverId;
        UUID secondId = firstId.equals(senderId) ? receiverId : senderId;

        log.debug("Locking accounts in order: {} then {}", firstId, secondId);

        // Fetch accounts with Pessimistic Write Lock
        Account firstAccount = accountRepository.findByIdForUpdate(firstId).orElse(null);
        Account secondAccount = accountRepository.findByIdForUpdate(secondId).orElse(null);

        if (firstAccount == null || secondAccount == null) {
            String missingId = firstAccount == null ? firstId.toString() : secondId.toString();
            log.error("One or both accounts not found. Missing ID: {}", missingId);
            publishPaymentFailed(event.getTransactionId(), "One or both accounts not found");
            return;
        }

        Account sender = firstId.equals(senderId) ? firstAccount : secondAccount;
        Account receiver = firstId.equals(receiverId) ? firstAccount : secondAccount;

        // 3. Balance Validation
        if (sender.getBalance().compareTo(event.getAmount()) < 0) {
            log.warn("Insufficient balance for sender: {}. Available: {}, Required: {}", 
                    senderId, sender.getBalance(), event.getAmount());
            publishPaymentFailed(event.getTransactionId(), "INSUFFICIENT_FUNDS");
            return;
        }

        // 4. Perform Transfer
        sender.setBalance(sender.getBalance().subtract(event.getAmount()));
        receiver.setBalance(receiver.getBalance().add(event.getAmount()));

        accountRepository.save(sender);
        accountRepository.save(receiver);
        log.debug("Updated balances. Sender {}: {}, Receiver {}: {}", 
                senderId, sender.getBalance(), receiverId, receiver.getBalance());

        // 5. Create double-entry audit records
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(event.getTransactionId())
                .accountId(senderId)
                .entryType(EntryType.DEBIT)
                .amount(event.getAmount())
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(event.getTransactionId())
                .accountId(receiverId)
                .entryType(EntryType.CREDIT)
                .amount(event.getAmount())
                .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);
        log.info("Ledger entries written successfully for transaction {}", event.getTransactionId());

        // 6. Publish Success
        publishPaymentCompleted(event.getTransactionId());
    }

    private void publishPaymentCompleted(UUID transactionId) {
        PaymentCompletedEvent successEvent = PaymentCompletedEvent.builder()
                .transactionId(transactionId)
                .completedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send("payment-completed", transactionId.toString(), successEvent);
        log.info("Published PaymentCompletedEvent for transaction {}", transactionId);
    }

    private void publishPaymentFailed(UUID transactionId, String reason) {
        PaymentFailedEvent failureEvent = PaymentFailedEvent.builder()
                .transactionId(transactionId)
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build();
        kafkaTemplate.send("payment-failed", transactionId.toString(), failureEvent);
        log.info("Published PaymentFailedEvent for transaction {} with reason: {}", transactionId, reason);
    }
}
