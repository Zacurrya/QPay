package com.example.qr_payments.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.example.qr_payments.config.SupabaseAuthProperties;
import com.example.qr_payments.dto.AuthResponse;
import com.example.qr_payments.model.Account;
import com.example.qr_payments.model.AccountType;
import com.example.qr_payments.model.AccountStatus;
import com.example.qr_payments.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupabaseAuthService {

    private final SupabaseAuthProperties supabaseProps;
    private final AccountRepository accountRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Derives a stable internal email from a username
    private String toEmail(String username) {
        return username.toLowerCase() + "@QPay.internal";
    }

    // REGISTER

    @Transactional
    public AuthResponse registerUser(String username, String password, BigDecimal initialBalance,
            String currency, AccountType accountType) {
        if (accountRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }

        String email = toEmail(username);

        // Create user via Supabase Admin API (POST /auth/v1/admin/users)
        HttpHeaders headers = adminHeaders();
        Map<String, Object> body = Map.of(
                "email", email,
                "password", password,
                "email_confirm", true // auto-confirm so no SMTP needed
        );

        ResponseEntity<String> supabaseResponse;
        try {
            supabaseResponse = restTemplate.postForEntity(
                    supabaseProps.getUrl() + "/auth/v1/admin/users",
                    new HttpEntity<>(body, headers),
                    String.class);
        } catch (HttpClientErrorException e) {
            log.error("Supabase register error: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Supabase registration failed: " + e.getMessage());
        }

        // Parse the Supabase user UUID
        UUID supabaseUserId = extractUserId(supabaseResponse.getBody());

        // Get a login token immediately so we can return an access token
        String accessToken = getAccessToken(email, password);

        // Create Account row in Supabase Postgres
        Account account = new Account();
        account.setSupabaseUserId(supabaseUserId);
        account.setUsername(username);
        account.setAccountType(accountType != null ? accountType : AccountType.CONSUMER);
        account.setCurrency(currency != null && !currency.trim().isEmpty() ? currency : "USD");
        account.setBalance(initialBalance != null ? initialBalance : BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);

        Account saved = accountRepository.save(account);

        return new AuthResponse(
                accessToken,
                supabaseUserId,
                username,
                saved.getId(),
                saved.getAccountType(),
                saved.getBalance(),
                saved.getCurrency());
    }

    // LOGIN

    public AuthResponse loginUser(String username, String password) {
        String email = toEmail(username);

        // Get access token from Supabase password grant
        String accessToken;
        String supabaseUserId;
        try {
            HttpHeaders headers = anonHeaders();

            Map<String, String> body = Map.of(
                    "email", email,
                    "password", password);

            ResponseEntity<String> tokenResponse = restTemplate.postForEntity(
                    supabaseProps.getUrl() + "/auth/v1/token?grant_type=password",
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode node = objectMapper.readTree(tokenResponse.getBody());
            accessToken = node.get("access_token").asText();
            supabaseUserId = node.get("user").get("id").asText();
        } catch (Exception e) {
            log.error("Supabase login error: {}", e.getMessage());
            throw new RuntimeException("Invalid credentials");
        }

        // Fetch account from DB
        Account account = accountRepository
                .findBySupabaseUserId(UUID.fromString(supabaseUserId))
                .orElseThrow(() -> new RuntimeException("Account not found for user"));

        return new AuthResponse(
                accessToken,
                UUID.fromString(supabaseUserId),
                account.getUsername(),
                account.getId(),
                account.getAccountType(),
                account.getBalance(),
                account.getCurrency());
    }

    // HELPERS

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", supabaseProps.getServiceRoleKey());
        h.set("Authorization", "Bearer " + supabaseProps.getServiceRoleKey());
        return h;
    }

    private String getAccessToken(String email, String password) {
        try {
            HttpHeaders headers = anonHeaders();

            Map<String, String> body = Map.of("email", email, "password", password);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    supabaseProps.getUrl() + "/auth/v1/token?grant_type=password",
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode node = objectMapper.readTree(resp.getBody());
            return node.get("access_token").asText();
        } catch (Exception e) {
            log.warn("Could not obtain access token after register: {}", e.getMessage());
            return "";
        }
    }

    private UUID extractUserId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return UUID.fromString(node.get("id").asText());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Supabase user id from response");
        }
    }

    private HttpHeaders anonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", supabaseProps.getAnonKey());
        h.set("Authorization", "Bearer " + supabaseProps.getAnonKey());
        return h;
    }
}
