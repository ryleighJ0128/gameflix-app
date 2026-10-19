package com.ryleigh.module4.user;

import com.ryleigh.module4.api.ApiException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        // Signature, algorithm, issuer, audience, expiration and subject were checked first.
        UUID id = UUID.fromString(jwt.getSubject());
        User user = users.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required or token invalid"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(UserResponse.from(user));
    }
}
