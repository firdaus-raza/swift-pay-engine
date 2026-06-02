package com.swiftpay.ledger.consumer;

import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentInitiatedConsumer {

    private final LedgerService ledgerService;

    @KafkaListener(topics = "payment-initiated", groupId = "ledger-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumePaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Consumed PaymentInitiatedEvent for transaction: {}", event.getTransactionId());
        try {
            ledgerService.processPayment(event);
        } catch (Exception ex) {
            log.error("Error processing transaction: {} in Ledger consumer", event.getTransactionId(), ex);
            throw ex; // Throwing exception will trigger standard Kafka consumer retries
        }
    }
}
