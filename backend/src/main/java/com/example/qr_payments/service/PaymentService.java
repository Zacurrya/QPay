package com.example.qr_payments.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.qr_payments.model.Account;
import com.example.qr_payments.model.EntryType;
import com.example.qr_payments.model.LedgerEntry;
import com.example.qr_payments.repository.AccountRepository;
import com.example.qr_payments.repository.LedgerEntryRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CurrencyService currencyService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public boolean processPayment(
            UUID debtorId,
            UUID creditorId,
            BigDecimal amount,
            String currency,
            String reference,
            String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()
                && !idempotencyService.isFirstRequest(idempotencyKey)) {
            log.info("Duplicate payment request skipped for idempotency key: {}", idempotencyKey);
            return false;
        }

        // Prevent self-payment
        if (debtorId.equals(creditorId)) {
            throw new RuntimeException("Can't send money to yourself");
        }

        // Pessimisticly lock both accounts
        Account debtor = accountRepository.findByIdForUpdate(debtorId)
                .orElseThrow(() -> new RuntimeException("Debtor account not found"));
        Account creditor = accountRepository.findByIdForUpdate(creditorId)
                .orElseThrow(() -> new RuntimeException("Creditor account not found"));

        // Fallback to debtor's currency if currency is missing (for backward compatibility)
        String requestCurrency = (currency != null && !currency.isBlank()) ? currency : debtor.getCurrency();

        // Convert the requested amount to the debtor's currency
        BigDecimal amountForDebtor = currencyService.convert(amount, requestCurrency, debtor.getCurrency());

        // Validate funds
        if (debtor.getBalance().compareTo(amountForDebtor) < 0) {
            throw new RuntimeException("Insufficient funds");
        }

        // Convert amount to creditor's currency
        BigDecimal amountForCreditor = currencyService.convert(amount, requestCurrency, creditor.getCurrency());

        // Process transaction
        debtor.setBalance(debtor.getBalance().subtract(amountForDebtor));
        creditor.setBalance(creditor.getBalance().add(amountForCreditor));

        accountRepository.save(debtor);
        accountRepository.save(creditor);

        // Record the immutable ledger entries for both the creditor and debtor
        UUID transactionId = UUID.randomUUID();

        LedgerEntry debitEntry = new LedgerEntry();
        debitEntry.setTransactionId(transactionId);
        debitEntry.setAccountId(debtor.getId());
        debitEntry.setAmount(amountForDebtor.negate()); // Money leaving the debtor
        debitEntry.setEntryType(EntryType.DEBIT);
        debitEntry.setReferenceCode(reference);

        LedgerEntry creditEntry = new LedgerEntry();
        creditEntry.setTransactionId(transactionId);
        creditEntry.setAccountId(creditor.getId());
        creditEntry.setAmount(amountForCreditor); // Money arriving at the creditor
        creditEntry.setEntryType(EntryType.CREDIT);
        creditEntry.setReferenceCode(reference);

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        return true;
    }
}
