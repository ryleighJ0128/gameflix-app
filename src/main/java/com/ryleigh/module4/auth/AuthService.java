package com.ryleigh.module4.auth;

import com.ryleigh.module4.api.ApiException;
import com.ryleigh.module4.user.User;
import com.ryleigh.module4.user.UserRepository;
import com.ryleigh.module4.user.UserResponse;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        // Missing accounts still perform bcrypt verification, reducing timing differences.
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public UserResponse register(CredentialsRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = new User(request.getUsername(), email, passwords.encode(request.getPassword()));
        try {
            // No exists-by-email precheck: the MySQL unique constraint resolves races.
            return UserResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateAccount(exception)) {
                throw new ApiException(HttpStatus.CONFLICT, "Email or username is already registered");
            }
            throw exception;
        }
    }

    public TokenResponse login(CredentialsRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()
                || request.getEmail().length() > 254 || !PasswordPolicy.isAcceptable(request.getPassword())) {
            throw invalidCredentials();
        }
        User user = users.findByEmail(normalizeEmail(request.getEmail())).orElse(null);
        String candidateHash = user == null ? dummyHash : user.getPasswordHash();
        boolean passwordMatches = passwords.matches(request.getPassword(), candidateHash);
        if (user == null || !passwordMatches) {
            throw invalidCredentials();
        }
        return tokens.issue(user.getId());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    private static boolean isDuplicateAccount(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }
}
