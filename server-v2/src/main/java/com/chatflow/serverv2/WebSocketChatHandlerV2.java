package com.chatflow.serverv2;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.protocol.MessageValidator;
import com.chatflow.protocol.QueueChatMessage;
import com.chatflow.protocol.ServerResponse;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

public class WebSocketChatHandlerV2 extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = OBJECT_MAPPER.getFactory();
    private static final Logger logger = LoggerFactory.getLogger(WebSocketChatHandlerV2.class);
    private static final AttributeKey<Boolean> JOINED_ATTR = AttributeKey.valueOf("joined");

    private final RabbitMqPublisher publisher;
    private final String serverId;
    private final RoomSessionRegistry roomSessionRegistry;

    public WebSocketChatHandlerV2(
            RabbitMqPublisher publisher,
            String serverId,
            RoomSessionRegistry roomSessionRegistry) {
        this.publisher = publisher;
        this.serverId = serverId;
        this.roomSessionRegistry = roomSessionRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("Only text frames supported");
        }

        String roomId = ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get();
        if (roomId == null || roomId.isBlank()) {
            roomId = "unknown";
        }

        try {
            ChatMessage message = parseMessage((TextWebSocketFrame) frame);
            MessageValidator.ValidationResult result = MessageValidator.validate(message);
            String serverTimestamp = Instant.now().toString();

            if (!result.isValid()) {
                writeResponse(ctx, ServerResponse.error(result.getErrorMessage(), serverTimestamp));
                return;
            }

            if (!updateJoinState(ctx, roomId, message, serverTimestamp)) {
                return;
            }

            QueueChatMessage queueMessage = QueueChatMessage.from(
                    message,
                    UUID.randomUUID().toString(),
                    roomId,
                    serverId,
                    extractClientIp(ctx));
            publisher.publish(queueMessage);

            writeResponse(ctx, ServerResponse.success(message, serverTimestamp));
        } catch (Exception e) {
            String serverTimestamp = Instant.now().toString();
            writeResponse(ctx, ServerResponse.error("Invalid request: " + e.getMessage(), serverTimestamp));
            logger.warn("Failed to process WebSocket message", e);
        }
    }

    private boolean updateJoinState(
            ChannelHandlerContext ctx,
            String roomId,
            ChatMessage message,
            String serverTimestamp) throws Exception {
        boolean joined = Boolean.TRUE.equals(ctx.channel().attr(JOINED_ATTR).get());
        if (message.getMessageType() == ChatMessage.MessageType.JOIN) {
            ctx.channel().attr(JOINED_ATTR).set(true);
            roomSessionRegistry.joinRoom(roomId, ctx.channel());
            return true;
        }
        if (message.getMessageType() == ChatMessage.MessageType.LEAVE) {
            ctx.channel().attr(JOINED_ATTR).set(false);
            roomSessionRegistry.leaveRoom(ctx.channel());
            return true;
        }
        if (!joined) {
            writeResponse(ctx, ServerResponse.error("User must JOIN before sending TEXT", serverTimestamp));
            return false;
        }
        return true;
    }

    private void writeResponse(ChannelHandlerContext ctx, ServerResponse response) throws Exception {
        String responseJson = OBJECT_MAPPER.writeValueAsString(response);
        ctx.write(new TextWebSocketFrame(responseJson));
    }

    private String extractClientIp(ChannelHandlerContext ctx) {
        if (!(ctx.channel().remoteAddress() instanceof InetSocketAddress)) {
            return "unknown";
        }
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        if (socketAddress.getAddress() == null) {
            return socketAddress.getHostString();
        }
        return socketAddress.getAddress().getHostAddress();
    }

    private ChatMessage parseMessage(TextWebSocketFrame frame) throws Exception {
        String userId = null;
        String username = null;
        String message = null;
        String timestamp = null;
        String messageTypeRaw = null;

        try (InputStream in = new ByteBufInputStream(frame.content(), false);
             JsonParser parser = JSON_FACTORY.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Expected JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.getCurrentName();
                if (fieldName == null) {
                    parser.skipChildren();
                    continue;
                }
                JsonToken valueToken = parser.nextToken();
                if (valueToken == null) {
                    break;
                }
                switch (fieldName) {
                    case "userId":
                        userId = parser.getValueAsString();
                        break;
                    case "username":
                        username = parser.getValueAsString();
                        break;
                    case "message":
                        message = parser.getValueAsString();
                        break;
                    case "timestamp":
                        timestamp = parser.getValueAsString();
                        break;
                    case "messageType":
                        messageTypeRaw = parser.getValueAsString();
                        break;
                    default:
                        parser.skipChildren();
                        break;
                }
            }
        }

        ChatMessage.MessageType messageType = null;
        if (messageTypeRaw != null) {
            try {
                messageType = ChatMessage.MessageType.valueOf(messageTypeRaw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("messageType must be one of TEXT, JOIN, or LEAVE");
            }
        }

        return new ChatMessage(userId, username, message, timestamp, messageType);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        roomSessionRegistry.leaveRoom(ctx.channel());
        logger.error("WebSocket handler error", cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        roomSessionRegistry.leaveRoom(ctx.channel());
        super.channelInactive(ctx);
    }
}
