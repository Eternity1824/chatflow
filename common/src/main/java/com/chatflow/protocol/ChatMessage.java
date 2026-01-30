package com.chatflow.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChatMessage {
    @JsonProperty("userId")
    private String userId;

    @JsonProperty("username")
    private String username;

    @JsonProperty("message")
    private String message;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("messageType")
    private MessageType messageType;

    public enum MessageType {
        TEXT, JOIN, LEAVE
    }

    public ChatMessage() {}

    public ChatMessage(String userId, String username, String message,
                       String timestamp, MessageType messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }

    // Getters
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public MessageType getMessageType() { return messageType; }

    // Setters
    public void setUserId(String userId) { this.userId = userId; }
    public void setUsername(String username) { this.username = username; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    @Override
    public String toString() {
        return String.format("ChatMessage{userId='%s', username='%s', message='%s', type=%s}",
                userId, username, message, messageType);
    }
}
