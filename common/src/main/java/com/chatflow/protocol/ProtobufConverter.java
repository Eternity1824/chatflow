package com.chatflow.protocol;

import com.chatflow.protocol.proto.MessageType;

public final class ProtobufConverter {
    private ProtobufConverter() {
    }

    public static com.chatflow.protocol.proto.QueueChatMessage toProto(QueueChatMessage pojo) {
        com.chatflow.protocol.proto.QueueChatMessage.Builder builder = com.chatflow.protocol.proto.QueueChatMessage.newBuilder();

        if (pojo.getMessageId() != null) {
            builder.setMessageId(pojo.getMessageId());
        }
        if (pojo.getRoomId() != null) {
            builder.setRoomId(pojo.getRoomId());
        }
        if (pojo.getUserId() != null) {
            builder.setUserId(pojo.getUserId());
        }
        if (pojo.getUsername() != null) {
            builder.setUsername(pojo.getUsername());
        }
        if (pojo.getMessage() != null) {
            builder.setMessage(pojo.getMessage());
        }
        if (pojo.getTimestamp() != null) {
            builder.setTimestamp(pojo.getTimestamp());
        }
        if (pojo.getMessageType() != null) {
            builder.setMessageType(toProtoMessageType(pojo.getMessageType()));
        }
        if (pojo.getRoomSequence() != null) {
            builder.setRoomSequence(pojo.getRoomSequence());
        }
        if (pojo.getServerId() != null) {
            builder.setServerId(pojo.getServerId());
        }
        if (pojo.getClientIp() != null) {
            builder.setClientIp(pojo.getClientIp());
        }

        return builder.build();
    }

    public static QueueChatMessage fromProto(com.chatflow.protocol.proto.QueueChatMessage proto) {
        return new QueueChatMessage(
                proto.getMessageId().isEmpty() ? null : proto.getMessageId(),
                proto.getRoomId().isEmpty() ? null : proto.getRoomId(),
                proto.getUserId().isEmpty() ? null : proto.getUserId(),
                proto.getUsername().isEmpty() ? null : proto.getUsername(),
                proto.getMessage().isEmpty() ? null : proto.getMessage(),
                proto.getTimestamp().isEmpty() ? null : proto.getTimestamp(),
                fromProtoMessageType(proto.getMessageType()),
                proto.getRoomSequence() == 0 ? null : proto.getRoomSequence(),
                proto.getServerId().isEmpty() ? null : proto.getServerId(),
                proto.getClientIp().isEmpty() ? null : proto.getClientIp()
        );
    }

    private static MessageType toProtoMessageType(ChatMessage.MessageType pojo) {
        switch (pojo) {
            case TEXT:
                return MessageType.TEXT;
            case JOIN:
                return MessageType.JOIN;
            case LEAVE:
                return MessageType.LEAVE;
            default:
                return MessageType.TEXT;
        }
    }

    private static ChatMessage.MessageType fromProtoMessageType(MessageType proto) {
        switch (proto) {
            case TEXT:
                return ChatMessage.MessageType.TEXT;
            case JOIN:
                return ChatMessage.MessageType.JOIN;
            case LEAVE:
                return ChatMessage.MessageType.LEAVE;
            default:
                return ChatMessage.MessageType.TEXT;
        }
    }
}
