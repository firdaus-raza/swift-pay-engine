package com.swiftpay.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftpay.gateway.client.LedgerClient;
import com.swiftpay.gateway.dto.AccountDto;
import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.exception.AccountNotFoundException;
import com.swiftpay.gateway.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PaymentControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private LedgerClient ledgerClient;

    private UUID senderId;
    private UUID receiverId;

    @BeforeEach
    void setUp() {
        senderId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        paymentRepository.deleteAll();
    }

    @Test
    void shouldAcceptPaymentWhenRequestIsValid() throws Exception {
        UUID transactionId = UUID.randomUUID();
        
        // Mock the ledger client response for a valid sender account with sufficient funds
        AccountDto mockAccount = AccountDto.builder()
                .id(senderId)
                .name("Sender User")
                .balance(new BigDecimal("1000.00"))
                .build();
        Mockito.when(ledgerClient.getAccount(senderId)).thenReturn(mockAccount);

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.message").value(containsString("accepted")));

        // Verify the payment was saved in the local DB with PENDING status
        assertTrue(paymentRepository.findById(transactionId).isPresent());
        assertTrue(paymentRepository.findById(transactionId).map(p -> p.getStatus().toString()).orElse("").contains("PENDING"));
    }

    @Test
    void shouldRejectPaymentWhenSenderHasInsufficientFunds() throws Exception {
        UUID transactionId = UUID.randomUUID();

        // Mock sender account with insufficient funds
        AccountDto mockAccount = AccountDto.builder()
                .id(senderId)
                .name("Sender User")
                .balance(new BigDecimal("50.00"))
                .build();
        Mockito.when(ledgerClient.getAccount(senderId)).thenReturn(mockAccount);

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Insufficient funds")));
    }

    @Test
    void shouldRejectPaymentWhenSenderAccountNotFound() throws Exception {
        UUID transactionId = UUID.randomUUID();

        // Mock sender account missing exception
        Mockito.when(ledgerClient.getAccount(senderId))
                .thenThrow(new AccountNotFoundException("Sender account not found: " + senderId));

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Sender account not found")));
    }

    @Test
    void shouldRejectDuplicatePaymentsForIdempotency() throws Exception {
        UUID transactionId = UUID.randomUUID();

        AccountDto mockAccount = AccountDto.builder()
                .id(senderId)
                .name("Sender User")
                .balance(new BigDecimal("1000.00"))
                .build();
        Mockito.when(ledgerClient.getAccount(senderId)).thenReturn(mockAccount);

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(transactionId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        // First request: accepted
        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // Second duplicate request: conflict (409)
        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("already processed")));
    }
}
