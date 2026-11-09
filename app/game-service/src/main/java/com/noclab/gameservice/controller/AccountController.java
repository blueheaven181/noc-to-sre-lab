package com.noclab.gameservice.controller;

import com.noclab.gameservice.service.AccountService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final String SESSION_ACCOUNT_EMAIL = "accountEmail";

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record SignupRequest(String email, String password) {}
    public record VerifyRequest(String email, String code) {}
    public record LoginRequest(String email, String password) {}

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody SignupRequest req) {
        AccountService.SignupResult result = accountService.signup(req.email(), req.password());

        if (result == AccountService.SignupResult.EMAIL_ALREADY_REGISTERED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "email already registered"));
        }

        return ResponseEntity.accepted()
                .body(Map.of("status", "signup accepted, check your email for a verification code"));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest req) {
        AccountService.VerifyResult result = accountService.verify(req.email(), req.code());

        return switch (result) {
            case OK -> ResponseEntity.ok(Map.of("status", "verified"));
            case ALREADY_VERIFIED -> ResponseEntity.ok(Map.of("status", "already verified"));
            case CODE_MISMATCH -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "code did not match"));
            case NO_PENDING_CODE -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "no pending code for this email - sign up first, or it expired"));
        };
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req, HttpSession session) {
        AccountService.LoginOutcome outcome = accountService.login(req.email(), req.password());

        return switch (outcome.result()) {
            case OK -> {
                session.setAttribute(SESSION_ACCOUNT_EMAIL, outcome.account().getEmail());
                yield ResponseEntity.ok(Map.of("status", "logged in", "email", outcome.account().getEmail()));
            }
            // Same generic message for both - don't let a login attempt reveal
            // whether an email address is registered at all.
            case NO_SUCH_ACCOUNT, WRONG_PASSWORD -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "invalid email or password"));
            case EMAIL_NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "email not verified yet"));
        };
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Object email = session.getAttribute(SESSION_ACCOUNT_EMAIL);
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "not logged in"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "logged in");
        body.put("email", email);
        return ResponseEntity.ok(body);
    }
}
