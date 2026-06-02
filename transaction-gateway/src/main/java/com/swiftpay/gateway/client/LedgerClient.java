package com.swiftpay.gateway.client;

import com.swiftpay.gateway.dto.AccountDto;
import com.swiftpay.gateway.exception.AccountNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Encapsulates outbound HTTP communication with the Ledger Service.
 * Isolates remote REST endpoints and maps HTTP response codes to domain exceptions.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerClient {

    private final RestClient ledgerRestClient;

    /**
     * Queries the Ledger Service to fetch account details.
     *
     * @param userId the identifier of the account owner
     * @return the account information including the current balance
     * @throws AccountNotFoundException if the account does not exist (HTTP 404)
     * @throws IllegalStateException if the Ledger Service is down or returning a server error
     */
    public AccountDto getAccount(UUID userId) {
        try {
            return ledgerRestClient.get()
                    .uri("/v1/ledgers/accounts/{userId}", userId)
                    .retrieve()
                    .body(AccountDto.class);
        } catch (HttpClientErrorException.NotFound ex) {
            log.warn("Account {} not found in Ledger Service", userId);
            throw new AccountNotFoundException("Sender account not found: " + userId);
        } catch (Exception ex) {
            log.error("Failed to fetch account detail for user {} from Ledger Service", userId, ex);
            throw new IllegalStateException("Ledger service is currently unavailable", ex);
        }
    }
}
