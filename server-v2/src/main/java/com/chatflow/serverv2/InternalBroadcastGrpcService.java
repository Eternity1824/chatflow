package com.chatflow.serverv2;

import com.chatflow.protocol.ProtobufConverter;
import com.chatflow.protocol.QueueChatMessage;
import com.chatflow.protocol.proto.BroadcastRequest;
import com.chatflow.protocol.proto.BroadcastResponse;
import com.chatflow.protocol.proto.InternalBroadcastGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InternalBroadcastGrpcService extends InternalBroadcastGrpc.InternalBroadcastImplBase {
    private static final Logger logger = LoggerFactory.getLogger(InternalBroadcastGrpcService.class);

    private final RoomSessionRegistry roomSessionRegistry;
    private final RecentMessageTracker messageTracker;
    private final String internalToken;

    public InternalBroadcastGrpcService(RoomSessionRegistry roomSessionRegistry, String internalToken) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.messageTracker = new RecentMessageTracker(200_000, 120_000L);
    }

    @Override
    public void broadcast(BroadcastRequest request, StreamObserver<BroadcastResponse> responseObserver) {
        try {
            com.chatflow.protocol.proto.QueueChatMessage protoMessage = request.getMessage();
            
            if (protoMessage.getRoomId() == null || protoMessage.getRoomId().isEmpty()) {
                responseObserver.onNext(BroadcastResponse.newBuilder()
                        .setSuccess(false)
                        .setDeliveredCount(0)
                        .setError("roomId is required")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            if (messageTracker.isDuplicate(protoMessage.getMessageId())) {
                responseObserver.onNext(BroadcastResponse.newBuilder()
                        .setSuccess(true)
                        .setDeliveredCount(0)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            QueueChatMessage message = ProtobufConverter.fromProto(protoMessage);
            int deliveredCount = roomSessionRegistry.broadcast(message);

            responseObserver.onNext(BroadcastResponse.newBuilder()
                    .setSuccess(true)
                    .setDeliveredCount(deliveredCount)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("gRPC broadcast error", e);
            responseObserver.onNext(BroadcastResponse.newBuilder()
                    .setSuccess(false)
                    .setDeliveredCount(0)
                    .setError("Internal server error: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }
}
