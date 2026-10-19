package com.ryleigh.module4.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(int status, String message, Map<String, String> errors) {
    public ApiError(int status, String message) {
        this(status, message, Map.of());
    }
}
