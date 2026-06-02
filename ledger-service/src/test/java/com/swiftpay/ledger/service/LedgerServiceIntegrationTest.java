package com.swiftpay.ledger.service;

import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.event.PaymentCompletedEvent;
import com.swiftpay.ledger.event.PaymentFailedEvent;
import com.swiftpay.ledger.model.Account;
import com.swiftpay.ledger.repository.AccountRepository;
import com.swiftpay.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class LedgerServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private UUID senderId;
    private UUID receiverId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();

        senderId = UUID.randomUUID();
        receiverId = UUID.randomUUID();

        Account sender = new Account();
        sender.setId(senderId);
        sender.setName("Sender");
        sender.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(sender);

        Account receiver = new Account();
        receiver.setId(receiverId);
        receiver.setName("Receiver");
        receiver.setBalance(new BigDecimal("500.00"));
        accountRepository.save(receiver);
    }

    @Test
    void shouldProcessPaymentSuccessfully() {
        UUID transactionId = UUID.randomUUID();
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .build();

        ledgerService.processPayment(event);

        // Verify balances
        Account updatedSender = accountRepository.findById(senderId).orElseThrow();
        Account updatedReceiver = accountRepository.findById(receiverId).orElseThrow();

        assertEquals(0, new BigDecimal("800.00").compareTo(updatedSender.getBalance()));
        assertEquals(0, new BigDecimal("700.00").compareTo(updatedReceiver.getBalance()));

        // Verify ledger entries created
        assertEquals(2, ledgerEntryRepository.findAll().size());
        assertTrue(ledgerEntryRepository.existsByTransactionId(transactionId));

        // Verify Kafka event published
        ArgumentCaptor<PaymentCompletedEvent> captor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(kafkaTemplate).send(eq("payment-completed"), eq(transactionId.toString()), captor.capture());
        
        assertEquals(transactionId, captor.getValue().getTransactionId());
    }

    @Test
    void shouldFailPaymentWhenInsufficientFunds() {
        UUID transactionId = UUID.randomUUID();
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("2000.00")) // More than balance
                .currency("USD")
                .build();

        ledgerService.processPayment(event);

        // Verify balances unchanged
        Account updatedSender = accountRepository.findById(senderId).orElseThrow();
        Account updatedReceiver = accountRepository.findById(receiverId).orElseThrow();

        assertEquals(0, new BigDecimal("1000.00").compareTo(updatedSender.getBalance()));
        assertEquals(0, new BigDecimal("500.00").compareTo(updatedReceiver.getBalance()));

        // Verify no ledger entries created
        assertEquals(0, ledgerEntryRepository.findAll().size());

        // Verify Kafka event published for failure
        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(kafkaTemplate).send(eq("payment-failed"), eq(transactionId.toString()), captor.capture());
        
        assertEquals(transactionId, captor.getValue().getTransactionId());
        assertEquals("INSUFFICIENT_FUNDS", captor.getValue().getReason());
    }

    @Test
    void shouldHandleIdempotencyCorrectly() {
        UUID transactionId = UUID.randomUUID();
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .build();

        // First processing
        ledgerService.processPayment(event);

        // Second processing (duplicate)
        ledgerService.processPayment(event);

        // Verify balances only deducted once
        Account updatedSender = accountRepository.findById(senderId).orElseThrow();
        assertEquals(0, new BigDecimal("800.00").compareTo(updatedSender.getBalance()));

        // Verify ledger entries only created once (2 entries for 1 transaction)
        assertEquals(2, ledgerEntryRepository.findAll().size());

        // Verify Kafka event published twice (once for initial, once for idempotency trigger)
        verify(kafkaTemplate, Mockito.times(2)).send(eq("payment-completed"), eq(transactionId.toString()), Mockito.any(PaymentCompletedEvent.class));
    }
}
