package com.chatflow.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MessageValidatorTest {

    @Test
    void validate_validMessage_returnsSuccess() {
        ChatMessage msg = new ChatMessage(
                "1",
                "user123",
                "hello",
                "2025-01-01T00:00:00+00:00",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertTrue(result.isValid());
        assertNull(result.getErrorMessage());
    }

    @Test
    void validate_nullMessage_returnsError() {
        MessageValidator.ValidationResult result = MessageValidator.validate(null);
        assertFalse(result.isValid());
        assertEquals("Message cannot be null", result.getErrorMessage());
    }

    @Test
    void validate_invalidUserId_returnsError() {
        ChatMessage msg = new ChatMessage(
                "0",
                "user123",
                "hello",
                "2025-01-01T00:00:00+00:00",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("userId must be between 1 and 100000", result.getErrorMessage());
    }

    @Test
    void validate_invalidUsername_returnsError() {
        ChatMessage msg = new ChatMessage(
                "1",
                "u!",
                "hello",
                "2025-01-01T00:00:00+00:00",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("username must be 3-20 characters", result.getErrorMessage());
    }

    @Test
    void validate_nonAlphanumericUsername_returnsError() {
        ChatMessage msg = new ChatMessage(
                "1",
                "user_name",
                "hello",
                "2025-01-01T00:00:00+00:00",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("username must be alphanumeric", result.getErrorMessage());
    }

    @Test
    void validate_emptyMessage_returnsError() {
        ChatMessage msg = new ChatMessage(
                "1",
                "user123",
                " ",
                "2025-01-01T00:00:00+00:00",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("message is required", result.getErrorMessage());
    }

    @Test
    void validate_invalidTimestamp_returnsError() {
        ChatMessage msg = new ChatMessage(
                "1",
                "user123",
                "hello",
                "not-a-timestamp",
                ChatMessage.MessageType.TEXT
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("timestamp must be valid ISO-8601 format", result.getErrorMessage());
    }

    @Test
    void validate_missingMessageType_returnsError() {
        ChatMessage msg = new ChatMessage(
                "1",
                "user123",
                "hello",
                "2025-01-01T00:00:00+00:00",
                null
        );

        MessageValidator.ValidationResult result = MessageValidator.validate(msg);
        assertFalse(result.isValid());
        assertEquals("messageType is required (TEXT, JOIN, or LEAVE)", result.getErrorMessage());
    }
}
