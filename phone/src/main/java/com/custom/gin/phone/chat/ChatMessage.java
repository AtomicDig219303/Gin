package com.custom.gin.phone.chat;

public class ChatMessage {
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    private final Role role;
    private final String content;
    private final byte[] mediaBytes;
    private final String mimeType;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.mediaBytes = null;
        this.mimeType = null;
    }

    public ChatMessage(Role role, String content, byte[] mediaBytes, String mimeType) {
        this.role = role;
        this.content = content;
        this.mediaBytes = mediaBytes;
        this.mimeType = mimeType;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public byte[] getMediaBytes() {
        return mediaBytes;
    }

    public String getMimeType() {
        return mimeType;
    }
}
