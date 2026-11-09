package com.example.qr_payments.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.qr_payments.dto.PaymentRequest;
import com.example.qr_payments.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<Void> processPayment(
            @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        boolean processed = paymentService.processPayment(
                request.debtorId(),
                request.creditorId(),
                request.amount(),
                request.currency(),
                request.reference(),
                idempotencyKey
        );

        if (!processed) {
            log.info("Returning cached 200 for duplicate idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
