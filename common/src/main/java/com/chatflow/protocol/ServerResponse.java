package com.chatflow.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerResponse {
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_BROADCAST = "BROADCAST";
    public static final String TYPE_ERROR = "ERROR";

    @JsonProperty("status")
    private String status;

    @JsonProperty("responseType")
    private String responseType;

    @JsonProperty("serverTimestamp")
    private String serverTimestamp;

    @JsonProperty("originalMessage")
    private ChatMessage originalMessage;

    @JsonProperty("messageId")
    private String messageId;

    @JsonProperty("roomId")
    private String roomId;

    @JsonProperty("roomSequence")
    private Long roomSequence;

    @JsonProperty("error")
    private String error;

    // Default constructor
    public ServerResponse() {}

    private ServerResponse(
            String status,
            String responseType,
            String serverTimestamp,
            ChatMessage originalMessage,
            String messageId,
            String roomId,
            Long roomSequence,
            String error) {
        this.status = status;
        this.responseType = responseType;
        this.serverTimestamp = serverTimestamp;
        this.originalMessage = originalMessage;
        this.messageId = messageId;
        this.roomId = roomId;
        this.roomSequence = roomSequence;
        this.error = error;
    }

    // Factory methods
    public static ServerResponse success(ChatMessage message, String serverTimestamp) {
        return successAck(message, serverTimestamp);
    }

    public static ServerResponse successAck(ChatMessage message, String serverTimestamp) {
        return new ServerResponse("success", TYPE_ACK, serverTimestamp, message, null, null, null, null);
    }

    public static ServerResponse broadcast(
            ChatMessage message,
            String serverTimestamp,
            String messageId,
            String roomId,
            Long roomSequence) {
        return new ServerResponse(
                "success",
                TYPE_BROADCAST,
                serverTimestamp,
                message,
                messageId,
                roomId,
                roomSequence,
                null);
    }

    public static ServerResponse error(String errorMessage, String serverTimestamp) {
        return new ServerResponse("error", TYPE_ERROR, serverTimestamp, null, null, null, null, errorMessage);
    }

    // Getters
    public String getStatus() { return status; }
    public String getResponseType() { return responseType; }
    public String getServerTimestamp() { return serverTimestamp; }
    public ChatMessage getOriginalMessage() { return originalMessage; }
    public String getMessageId() { return messageId; }
    public String getRoomId() { return roomId; }
    public Long getRoomSequence() { return roomSequence; }
    public String getError() { return error; }

    // Setters
    public void setStatus(String status) { this.status = status; }
    public void setResponseType(String responseType) { this.responseType = responseType; }
    public void setServerTimestamp(String serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }
    public void setOriginalMessage(ChatMessage originalMessage) {
        this.originalMessage = originalMessage;
    }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setRoomSequence(Long roomSequence) { this.roomSequence = roomSequence; }
    public void setError(String error) { this.error = error; }

}
