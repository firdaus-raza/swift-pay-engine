package com.swiftpay.ledger.controller;

import com.swiftpay.ledger.model.Account;
import com.swiftpay.ledger.model.LedgerEntry;
import com.swiftpay.ledger.repository.AccountRepository;
import com.swiftpay.ledger.repository.LedgerEntryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/ledgers")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ledger Service", description = "Endpoints for accounts and transaction history audits")
public class LedgerController {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping("/accounts")
    @Operation(summary = "Initialize a new account", description = "Creates a test account with a balance for manual verification.")
    public ResponseEntity<Account> createAccount(@RequestBody Account account) {
        log.info("Initializing account: {}", account);
        Account saved = accountRepository.save(account);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/accounts/{userId}")
    @Operation(summary = "Get account details", description = "Retrieves current balance and details for a specific user ID.")
    public ResponseEntity<Account> getAccount(@PathVariable("userId") UUID userId) {
        log.info("Fetching account balance for user: {}", userId);
        return accountRepository.findById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts/{userId}/history")
    @Operation(summary = "Get ledger entries history", description = "Returns a list of all debit and credit ledger entries for a user.")
    public ResponseEntity<List<LedgerEntry>> getHistory(@PathVariable("userId") UUID userId) {
        log.info("Fetching transaction history for user: {}", userId);
        List<LedgerEntry> history = ledgerEntryRepository.findByAccountId(userId);
        return ResponseEntity.ok(history);
    }
}
