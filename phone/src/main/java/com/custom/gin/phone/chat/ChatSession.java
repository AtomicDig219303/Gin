package com.custom.gin.phone.chat;

import java.util.ArrayList;
import java.util.List;

public class ChatSession {
    // 20 turns (User + Assistant) = 40 messages max
    private static final int MAX_MESSAGES = 40;

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage message) {
        messages.add(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public void clear() {
        messages.clear();
    }
}
