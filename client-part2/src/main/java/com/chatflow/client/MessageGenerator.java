package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
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

    private final BlockingQueue<MessageTemplate> messageQueue;
    private final int totalMessages;
    private final Random random;
    private final boolean[] joinedRooms;

    public MessageGenerator(BlockingQueue<MessageTemplate> messageQueue, int totalMessages, int roomCount) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
        this.random = new Random();
        this.joinedRooms = new boolean[roomCount + 1];
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < totalMessages; i++) {
                MessageTemplate template = generateTemplate();
                messageQueue.put(template);
            }
            System.out.println("Message generation completed: " + totalMessages + " messages");
        } catch (Exception e) {
            System.err.println("Error in message generator: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private MessageTemplate generateTemplate() {
        int userId = random.nextInt(100000) + 1;
        String username = "user" + userId;
        String message = PREDEFINED_MESSAGES[random.nextInt(PREDEFINED_MESSAGES.length)];
        int roomId = random.nextInt(20) + 1;

        ChatMessage.MessageType messageType = pickMessageType(roomId);

        return new MessageTemplate(
                String.valueOf(userId),
                username,
                message,
                messageType,
                String.valueOf(roomId)
        );
    }

    private ChatMessage.MessageType pickMessageType(int roomId) {
        int typeRoll = random.nextInt(100);
        ChatMessage.MessageType chosen;
        if (typeRoll < 90) {
            chosen = ChatMessage.MessageType.TEXT;
        } else if (typeRoll < 95) {
            chosen = ChatMessage.MessageType.JOIN;
        } else {
            chosen = ChatMessage.MessageType.LEAVE;
        }

        boolean joined = joinedRooms[roomId];
        if (!joined) {
            chosen = ChatMessage.MessageType.JOIN;
        }

        if (chosen == ChatMessage.MessageType.JOIN) {
            joinedRooms[roomId] = true;
        } else if (chosen == ChatMessage.MessageType.LEAVE) {
            joinedRooms[roomId] = false;
        }

        return chosen;
    }
}
