package com.swiftpay.gateway.controller;

import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.dto.PaymentResponse;
import com.swiftpay.gateway.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment Gateway", description = "Endpoints for initiating client P2P payments")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(summary = "Initiate a P2P money transfer", description = "Accepts payment parameters, verifies idempotency, saves transaction as PENDING, and triggers async processing.")
    @ApiResponse(responseCode = "202", description = "Payment accepted for processing")
    @ApiResponse(responseCode = "400", description = "Validation errors in request body")
    @ApiResponse(responseCode = "409", description = "Duplicate transaction id (Idempotency check failed)")
    public ResponseEntity<PaymentResponse> initiatePayment(@Valid @RequestBody PaymentRequest request) {
        log.info("Received request to initiate payment: {}", request);
        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
