package com.ryleigh.module4.auth;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    public static boolean isAcceptable(String password) {
        // bcrypt has a byte limit. A character limit alone misses multibyte input.
        return password != null
                && password.length() >= 12
                && password.length() <= 64
                && password.indexOf('\0') < 0
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
