package com.noclab.gameservice.service;

import com.noclab.gameservice.model.Account;
import com.noclab.gameservice.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

/**
 * Stage 1 of the casino-lab work: signup, email verification via a
 * one-time code, and login. Deliberately keeps all of that logic out of
 * the controller, since there's real business logic here (hashing,
 * one-time-code lifecycle, verification state) - the existing
 * GameController stays thin because the leaderboard logic genuinely is
 * simple; this isn't, so it gets its own layer.
 */
@Service
public class AccountService {

    // One-time code lives in Redis, not a database table - it's meant to
    // expire on its own and never needs to be queried or reported on,
    // which is exactly what Redis's own TTL is for.
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final String OTP_KEY_PREFIX = "otp:";

    private final AccountRepository accountRepository;
    private final StringRedisTemplate redisTemplate;
    private final JavaMailSender mailSender;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @Value("${app.mail.from:no-reply@noc-to-sre-lab.local}")
    private String mailFrom;

    public AccountService(AccountRepository accountRepository,
                           StringRedisTemplate redisTemplate,
                           JavaMailSender mailSender) {
        this.accountRepository = accountRepository;
        this.redisTemplate = redisTemplate;
        this.mailSender = mailSender;
    }

    public enum SignupResult { OK, EMAIL_ALREADY_REGISTERED }

    public SignupResult signup(String email, String rawPassword) {
        if (accountRepository.existsByEmail(email)) {
            return SignupResult.EMAIL_ALREADY_REGISTERED;
        }

        Account account = new Account(email, passwordEncoder.encode(rawPassword));
        accountRepository.save(account);

        String code = generateOtp();
        redisTemplate.opsForValue().set(OTP_KEY_PREFIX + email, code, OTP_TTL);
        sendOtpEmail(email, code);

        return SignupResult.OK;
    }

    public enum VerifyResult { OK, NO_PENDING_CODE, CODE_MISMATCH, ALREADY_VERIFIED }

    public VerifyResult verify(String email, String submittedCode) {
        Optional<Account> maybeAccount = accountRepository.findByEmail(email);
        if (maybeAccount.isEmpty()) {
            return VerifyResult.NO_PENDING_CODE;
        }
        Account account = maybeAccount.get();
        if (account.isEmailVerified()) {
            return VerifyResult.ALREADY_VERIFIED;
        }

        String key = OTP_KEY_PREFIX + email;
        String storedCode = redisTemplate.opsForValue().get(key);
        if (storedCode == null) {
            return VerifyResult.NO_PENDING_CODE; // never requested, or expired
        }
        if (!storedCode.equals(submittedCode)) {
            return VerifyResult.CODE_MISMATCH;
        }

        account.setEmailVerified(true);
        accountRepository.save(account);
        redisTemplate.delete(key); // one-time use - burn it once it's actually been used

        return VerifyResult.OK;
    }

    public enum LoginResult { OK, NO_SUCH_ACCOUNT, WRONG_PASSWORD, EMAIL_NOT_VERIFIED }

    public record LoginOutcome(LoginResult result, Account account) {}

    public LoginOutcome login(String email, String rawPassword) {
        Optional<Account> maybeAccount = accountRepository.findByEmail(email);
        if (maybeAccount.isEmpty()) {
            // Deliberately the same generic failure as a wrong password below -
            // never confirm or deny whether an email is registered.
            return new LoginOutcome(LoginResult.NO_SUCH_ACCOUNT, null);
        }
        Account account = maybeAccount.get();
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            return new LoginOutcome(LoginResult.WRONG_PASSWORD, null);
        }
        if (!account.isEmailVerified()) {
            return new LoginOutcome(LoginResult.EMAIL_NOT_VERIFIED, null);
        }
        return new LoginOutcome(LoginResult.OK, account);
    }

    private String generateOtp() {
        // 6-digit numeric code, zero-padded (e.g. "042917")
        int n = random.nextInt(1_000_000);
        return String.format("%06d", n);
    }

    private void sendOtpEmail(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(toEmail);
        message.setSubject("Your verification code");
        message.setText("Your noc-to-sre-lab verification code is: " + code
                + "\n\nThis code expires in 10 minutes. (This is a lab - no real email "
                + "was sent; check the mail-catcher's web UI instead.)");
        mailSender.send(message);
    }
}
