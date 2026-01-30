package com.chatflow.protocol;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class MessageValidator {

    /**
     * Validates a ChatMessage according to the specification.
     * Returns ValidationResult with success or error details.
     */
    public static ValidationResult validate(ChatMessage message) {
        if (message == null) {
            return ValidationResult.error("Message cannot be null");
        }

        // 1. Validate userId (must be between 1 and 100000)
        ValidationResult userIdResult = validateUserId(message.getUserId());
        if (!userIdResult.isValid()) {
            return userIdResult;
        }

        // 2. Validate username (3-20 alphanumeric characters)
        ValidationResult usernameResult = validateUsername(message.getUsername());
        if (!usernameResult.isValid()) {
            return usernameResult;
        }

        // 3. Validate message (1-500 characters)
        ValidationResult messageResult = validateMessage(message.getMessage());
        if (!messageResult.isValid()) {
            return messageResult;
        }

        // 4. Validate timestamp (ISO-8601)
        ValidationResult timestampResult = validateTimestamp(message.getTimestamp());
        if (!timestampResult.isValid()) {
            return timestampResult;
        }

        // 5. Validate messageType
        ValidationResult typeResult = validateMessageType(message.getMessageType());
        if (!typeResult.isValid()) {
            return typeResult;
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ValidationResult.error("userId is required");
        }

        try {
            int id = Integer.parseInt(userId);
            if (id < 1 || id > 100000) {
                return ValidationResult.error("userId must be between 1 and 100000");
            }
        } catch (NumberFormatException e) {
            return ValidationResult.error("userId must be a valid number");
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return ValidationResult.error("username is required");
        }

        if (username.length() < 3 || username.length() > 20) {
            return ValidationResult.error("username must be 3-20 characters");
        }

        if (!username.matches("^[a-zA-Z0-9]+$")) {
            return ValidationResult.error("username must be alphanumeric");
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return ValidationResult.error("message is required");
        }

        if (message.length() < 1 || message.length() > 500) {
            return ValidationResult.error("message must be 1-500 characters");
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateTimestamp(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return ValidationResult.error("timestamp is required");
        }

        try {
            // Try to parse as ISO-8601
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp);
        } catch (DateTimeParseException e) {
            return ValidationResult.error("timestamp must be valid ISO-8601 format");
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateMessageType(ChatMessage.MessageType messageType) {
        if (messageType == null) {
            return ValidationResult.error("messageType is required (TEXT, JOIN, or LEAVE)");
        }

        return ValidationResult.success();
    }

    /**
     * Result of validation containing success status and optional error message.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}