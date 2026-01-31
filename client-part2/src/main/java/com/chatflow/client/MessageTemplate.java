package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;

public class MessageTemplate {
    private final String userId;
    private final String username;
    private final String message;
    private final ChatMessage.MessageType messageType;
    private final String roomId;

    public MessageTemplate(String userId, String username, String message,
                           ChatMessage.MessageType messageType, String roomId) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.messageType = messageType;
        this.roomId = roomId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public ChatMessage.MessageType getMessageType() {
        return messageType;
    }

    public String getRoomId() {
        return roomId;
    }
}
