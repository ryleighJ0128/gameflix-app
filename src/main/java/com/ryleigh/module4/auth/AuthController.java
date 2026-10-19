package com.ryleigh.module4.auth;

import com.ryleigh.module4.user.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody CredentialsRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(auth.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody CredentialsRequest request) {
        // This endpoint deliberately returns the same 401 for all credential failures.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(request));
    }
}
