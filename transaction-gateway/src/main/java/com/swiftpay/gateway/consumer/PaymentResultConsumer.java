package com.swiftpay.gateway.consumer;

import com.swiftpay.gateway.event.PaymentCompletedEvent;
import com.swiftpay.gateway.event.PaymentFailedEvent;
import com.swiftpay.gateway.model.PaymentStatus;
import com.swiftpay.gateway.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentResultConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics = "payment-completed", groupId = "gateway-group")
    public void consumePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent for transaction: {}", event.getTransactionId());
        try {
            paymentService.updatePaymentStatus(event.getTransactionId(), PaymentStatus.SUCCESS, null);
        } catch (Exception ex) {
            log.error("Failed to update payment status to SUCCESS for transaction: {}", event.getTransactionId(), ex);
        }
    }

    @KafkaListener(topics = "payment-failed", groupId = "gateway-group")
    public void consumePaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent for transaction: {} with reason: {}", event.getTransactionId(), event.getReason());
        try {
            paymentService.updatePaymentStatus(event.getTransactionId(), PaymentStatus.FAILED, event.getReason());
        } catch (Exception ex) {
            log.error("Failed to update payment status to FAILED for transaction: {}", event.getTransactionId(), ex);
        }
    }
}
