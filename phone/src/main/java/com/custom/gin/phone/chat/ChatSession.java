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

    /**
     * Releases media byte arrays from all messages except the most recent one.
     * Call after sending messages to the LLM API to prevent OOM from accumulated
     * audio/image data across many conversation turns.
     */
    public void clearMediaFromHistory() {
        for (int i = 0; i < messages.size() - 1; i++) {
            messages.get(i).clearMedia();
        }
    }
}
