package com.swiftpay.gateway.service;

import com.swiftpay.gateway.event.PaymentInitiatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Transaction committed successfully. Publishing PaymentInitiatedEvent to Kafka for transaction: {}", event.getTransactionId());
        try {
            kafkaTemplate.send("payment-initiated", event.getTransactionId().toString(), event);
        } catch (Exception ex) {
            log.error("Failed to publish event to Kafka for transaction: {}", event.getTransactionId(), ex);
            // In a production app, this would be backed up by a scheduled outbox publisher poll or retry
        }
    }
}
