package com.example.qr_payments.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.qr_payments.model.AccountType;
import com.example.qr_payments.model.AccountStatus;

public record AccountResponse(
    UUID id,
    AccountType accountType,
    String currency,
    BigDecimal balance,
    AccountStatus status
) {}
