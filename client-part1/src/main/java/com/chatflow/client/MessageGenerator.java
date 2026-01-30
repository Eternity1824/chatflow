package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

public class MessageGenerator implements Runnable {
    private static final String[] PREDEFINED_MESSAGES = {
            "Hello everyone!", "How are you doing?", "Great to be here!",
            "Anyone online?", "What's up?", "Good morning!",
            "Good evening!", "See you later!", "Thanks for the help!",
            "That's interesting!", "I agree with that.", "Nice to meet you!",
            "Let's discuss this.", "What do you think?", "Sounds good to me.",
            "I'm working on a project.", "Can anyone help?", "This is fun!",
            "Looking forward to it.", "Count me in!", "Absolutely!",
            "Not sure about that.", "Maybe later.", "I'll check it out.",
            "Thanks for sharing!", "Appreciate it!", "No problem!",
            "You're welcome!", "My pleasure!", "Anytime!",
            "Let me know.", "Keep me posted.", "Will do!",
            "Got it!", "Understood.", "Makes sense.",
            "Interesting point.", "Good question.", "Fair enough.",
            "I see what you mean.", "That works for me.", "Sounds like a plan.",
            "Let's do it!", "I'm in!", "Perfect!",
            "Awesome!", "Cool!", "Nice!",
            "Great job!", "Well done!", "Congratulations!"
    };

    private final BlockingQueue<String> messageQueue;
    private final int totalMessages;
    private final ObjectMapper objectMapper;
    private final Random random;

    public MessageGenerator(BlockingQueue<String> messageQueue, int totalMessages) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
        this.objectMapper = new ObjectMapper();
        this.random = new Random();
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < totalMessages; i++) {
                String jsonMessage = generateMessage();
                messageQueue.put(jsonMessage);
            }
            System.out.println("Message generation completed: " + totalMessages + " messages");
        } catch (Exception e) {
            System.err.println("Error in message generator: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String generateMessage() throws Exception {
        int userId = random.nextInt(100000) + 1;
        String username = "user" + userId;
        String message = PREDEFINED_MESSAGES[random.nextInt(PREDEFINED_MESSAGES.length)];
        int roomId = random.nextInt(20) + 1;
        String timestamp = Instant.now().toString();

        ChatMessage.MessageType messageType;
        int typeRoll = random.nextInt(100);
        if (typeRoll < 90) {
            messageType = ChatMessage.MessageType.TEXT;
        } else if (typeRoll < 95) {
            messageType = ChatMessage.MessageType.JOIN;
        } else {
            messageType = ChatMessage.MessageType.LEAVE;
        }

        ChatMessage chatMessage = new ChatMessage(
                String.valueOf(userId),
                username,
                message,
                timestamp,
                messageType
        );

        return objectMapper.writeValueAsString(chatMessage) + "|" + roomId;
    }
}
