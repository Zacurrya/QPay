package com.example.qr_payments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.qr_payments.dto.AccountResponse;
import com.example.qr_payments.model.Account;
import com.example.qr_payments.model.AccountType;
import com.example.qr_payments.model.AccountStatus;
import com.example.qr_payments.repository.AccountRepository;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(AccountType type, String currency) {
        
        // Create new account
        Account account = new Account();
        account.setAccountType(type);
        account.setCurrency(currency.toUpperCase()); // Standardise currency codes
        account.setBalance(BigDecimal.ZERO); 
        account.setStatus(AccountStatus.ACTIVE);
        
        // Save to Supabase
        Account savedAccount = accountRepository.save(account);
        
        // Return account response DTO
        return mapToResponse(savedAccount);
    }

    public AccountResponse getAccount(UUID id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Account not found"));
            
        return mapToResponse(account);
    }

    // helper method to map account to response DTO
    private AccountResponse mapToResponse(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getAccountType(),
            account.getCurrency(),
            account.getBalance(),
            account.getStatus()
        );
    }
}