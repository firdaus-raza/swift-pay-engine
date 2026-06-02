package com.swiftpay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${services.ledger.url:http://localhost:8082}")
    private String ledgerServiceUrl;

    @Bean
    public RestClient ledgerRestClient() {
        return RestClient.builder()
                .baseUrl(ledgerServiceUrl)
                .build();
    }
}
