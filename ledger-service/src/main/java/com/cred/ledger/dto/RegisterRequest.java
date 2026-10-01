package com.cred.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]{3,64}$",
                message = "username must be 3-64 characters: letters, digits, '.', '_' or '-'")
        String username,

        // bcrypt only uses the first 72 bytes, so longer passwords would be silently truncated
        @NotBlank @Size(min = 8, max = 72, message = "password must be 8-72 characters") String password
) {
}
