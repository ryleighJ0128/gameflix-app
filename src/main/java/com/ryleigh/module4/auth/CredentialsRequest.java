package com.ryleigh.module4.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Deliberately no generated toString(): credentials must not enter logs.
public class CredentialsRequest {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_]{3,50}", message = "must contain 3 to 50 letters, digits, or underscores")
    private String username;

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @ValidPassword
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
