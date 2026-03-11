package com.chatflow.consumer;

import com.chatflow.protocol.proto.BroadcastRequest;
import com.chatflow.protocol.proto.BroadcastResponse;
import com.chatflow.protocol.proto.InternalBroadcastGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GrpcBroadcastClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GrpcBroadcastClient.class);

    private final List<TargetChannel> channels;
    private final long timeoutMs;

    public GrpcBroadcastClient(List<String> targets, long timeoutMs) {
        this.channels = new ArrayList<>(targets.size());
        this.timeoutMs = Math.max(500L, timeoutMs);

        for (String target : targets) {
            try {
                String[] parts = parseTarget(target);
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);

                ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .build();
                InternalBroadcastGrpc.InternalBroadcastStub stub = InternalBroadcastGrpc.newStub(channel);
                channels.add(new TargetChannel(target, channel, stub));
            } catch (Exception e) {
                logger.error("Failed to create gRPC channel for target {}", target, e);
            }
        }
    }

    public CompletableFuture<Boolean> broadcastAsync(com.chatflow.protocol.proto.QueueChatMessage protoMessage) {
        if (channels.isEmpty()) {
            logger.error("No gRPC channels available. Check CHATFLOW_BROADCAST_TARGETS.");
            return CompletableFuture.completedFuture(false);
        }

        BroadcastRequest request = BroadcastRequest.newBuilder()
                .setMessage(protoMessage)
                .build();

        int channelCount = channels.size();
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] futures = new CompletableFuture[channelCount];
        
        for (int i = 0; i < channelCount; i++) {
            futures[i] = sendToTarget(channels.get(i), request, protoMessage.getMessageId());
        }

        return CompletableFuture.allOf(futures)
                .handle((unused, error) -> {
                    boolean allSucceeded = error == null;
                    for (CompletableFuture<Boolean> future : futures) {
                        if (!future.getNow(false)) {
                            allSucceeded = false;
                        }
                    }
                    return allSucceeded;
                });
    }

    private CompletableFuture<Boolean> sendToTarget(TargetChannel targetChannel, BroadcastRequest request, String messageId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        io.grpc.stub.StreamObserver<BroadcastResponse> responseObserver = new io.grpc.stub.StreamObserver<BroadcastResponse>() {
            @Override
            public void onNext(BroadcastResponse response) {
                if (response.getSuccess()) {
                    future.complete(true);
                } else {
                    logger.warn("gRPC broadcast to {} failed: {}", targetChannel.target, response.getError());
                    future.complete(false);
                }
            }

            @Override
            public void onError(Throwable t) {
                if (t instanceof StatusRuntimeException) {
                    logger.warn("gRPC broadcast to {} failed: {}", targetChannel.target, ((StatusRuntimeException) t).getStatus());
                } else {
                    logger.warn("gRPC broadcast to {} failed", targetChannel.target, t);
                }
                future.complete(false);
            }

            @Override
            public void onCompleted() {
                if (!future.isDone()) {
                    future.complete(false);
                }
            }
        };

        try {
            targetChannel.stub
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .broadcast(request, responseObserver);
        } catch (Exception e) {
            logger.warn("Failed to initiate gRPC broadcast to {} for message {}", targetChannel.target, messageId, e);
            future.complete(false);
        }

        return future;
    }

    private String[] parseTarget(String target) {
        if (target.startsWith("grpc://")) {
            target = target.substring(7);
        }
        String[] parts = target.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid gRPC target format: " + target + " (expected host:port)");
        }
        return parts;
    }

    @Override
    public void close() {
        for (TargetChannel targetChannel : channels) {
            try {
                targetChannel.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while closing gRPC channel for {}", targetChannel.target);
                targetChannel.channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class TargetChannel {
        private final String target;
        private final ManagedChannel channel;
        private final InternalBroadcastGrpc.InternalBroadcastStub stub;

        private TargetChannel(String target, ManagedChannel channel, InternalBroadcastGrpc.InternalBroadcastStub stub) {
            this.target = target;
            this.channel = channel;
            this.stub = stub;
        }
    }
}
