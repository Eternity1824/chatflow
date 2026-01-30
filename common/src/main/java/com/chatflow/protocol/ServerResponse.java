package com.chatflow.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerResponse {
    @JsonProperty("status")
    private String status;

    @JsonProperty("serverTimestamp")
    private String serverTimestamp;

    @JsonProperty("originalMessage")
    private ChatMessage originalMessage;

    @JsonProperty("error")
    private String error;

    // Default constructor
    public ServerResponse() {}

    private ServerResponse(String status, String serverTimestamp,
                           ChatMessage originalMessage, String error) {
        this.status = status;
        this.serverTimestamp = serverTimestamp;
        this.originalMessage = originalMessage;
        this.error = error;
    }

    // Factory methods
    public static ServerResponse success(ChatMessage message, String serverTimestamp) {
        return new ServerResponse("success", serverTimestamp, message, null);
    }

    public static ServerResponse error(String errorMessage, String serverTimestamp) {
        return new ServerResponse("error", serverTimestamp, null, errorMessage);
    }

    // Getters
    public String getStatus() { return status; }
    public String getServerTimestamp() { return serverTimestamp; }
    public ChatMessage getOriginalMessage() { return originalMessage; }
    public String getError() { return error; }

    // Setters
    public void setStatus(String status) { this.status = status; }
    public void setServerTimestamp(String serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }
    public void setOriginalMessage(ChatMessage originalMessage) {
        this.originalMessage = originalMessage;
    }
    public void setError(String error) { this.error = error; }

}
