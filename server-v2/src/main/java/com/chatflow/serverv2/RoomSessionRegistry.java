package com.chatflow.serverv2;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.protocol.QueueChatMessage;
import com.chatflow.protocol.ServerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class RoomSessionRegistry {
    private static final Logger logger = LoggerFactory.getLogger(RoomSessionRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, ChannelGroup> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> channelRoomMap = new ConcurrentHashMap<>();

    public void joinRoom(String roomId, Channel channel) {
        if (roomId == null || roomId.isBlank() || channel == null) {
            return;
        }
        String channelId = channel.id().asShortText();
        String previousRoom = channelRoomMap.put(channelId, roomId);
        if (previousRoom != null && !previousRoom.equals(roomId)) {
            removeFromRoom(previousRoom, channel);
        }
        ChannelGroup group = roomSessions.computeIfAbsent(
                roomId,
                key -> new DefaultChannelGroup("room-" + key, GlobalEventExecutor.INSTANCE));
        group.add(channel);
    }

    public void leaveRoom(Channel channel) {
        if (channel == null) {
            return;
        }
        String channelId = channel.id().asShortText();
        String roomId = channelRoomMap.remove(channelId);
        if (roomId == null) {
            return;
        }
        removeFromRoom(roomId, channel);
    }

    public int broadcast(QueueChatMessage queueMessage) throws Exception {
        if (queueMessage == null || queueMessage.getRoomId() == null || queueMessage.getRoomId().isBlank()) {
            return 0;
        }
        ChannelGroup group = roomSessions.get(queueMessage.getRoomId());
        if (group == null || group.isEmpty()) {
            return 0;
        }

        ChatMessage chatMessage = new ChatMessage(
                queueMessage.getUserId(),
                queueMessage.getUsername(),
                queueMessage.getMessage(),
                queueMessage.getTimestamp(),
                queueMessage.getMessageType());
        ServerResponse response = ServerResponse.broadcast(
                chatMessage,
                Instant.now().toString(),
                queueMessage.getMessageId(),
                queueMessage.getRoomId(),
                queueMessage.getRoomSequence());
        String payload = OBJECT_MAPPER.writeValueAsString(response);
        group.writeAndFlush(new TextWebSocketFrame(payload));
        return group.size();
    }

    public int activeRoomCount() {
        return roomSessions.size();
    }

    public int activeSessionCount() {
        return channelRoomMap.size();
    }

    private void removeFromRoom(String roomId, Channel channel) {
        ChannelGroup group = roomSessions.get(roomId);
        if (group == null) {
            return;
        }
        group.remove(channel);
        if (group.isEmpty()) {
            roomSessions.remove(roomId, group);
        }
        logger.debug("Channel {} removed from room {}", channel.id().asShortText(), roomId);
    }
}
