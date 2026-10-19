package com.ryleigh.module4.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
