package com.custom.gin.phone.chat;

import java.util.ArrayList;
import java.util.List;

public class ChatSession {
    // 20 turns (User + Assistant) = 40 messages max
    private static final int MAX_MESSAGES = 40;

    private final List<ChatMessage> messages = new ArrayList<>();

    public synchronized void addMessage(ChatMessage message) {
        messages.add(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    public synchronized void removeMessage(ChatMessage message) {
        messages.remove(message);
    }

    public synchronized List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    /**
     * Releases media byte arrays from all messages in history.
     * Call after building/sending the LLM API request to prevent OOM and avoid
     * re-encoding/re-uploading historical audio/image payloads on future turns.
     */
    public synchronized void clearMediaFromHistory() {
        for (int i = 0; i < messages.size(); i++) {
            messages.get(i).clearMedia();
        }
    }
}
