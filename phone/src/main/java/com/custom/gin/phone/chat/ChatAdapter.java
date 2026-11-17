package com.custom.gin.phone.chat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.custom.gin.phone.R;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {
    private static final int MAX_DISPLAY_MESSAGES = 100;

    private final List<ChatMessage> messages = new ArrayList<>();

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        if (newMessages != null) {
            int start = Math.max(0, newMessages.size() - MAX_DISPLAY_MESSAGES);
            messages.addAll(newMessages.subList(start, newMessages.size()));
        }
        notifyDataSetChanged();
    }

    public void clearMessages() {
        int size = messages.size();
        if (size > 0) {
            messages.clear();
            notifyItemRangeRemoved(0, size);
        }
    }

    /** Efficiently append a single message with bounded memory. */
    public void addMessage(ChatMessage message) {
        if (messages.size() >= MAX_DISPLAY_MESSAGES) {
            messages.remove(0);
            notifyItemRemoved(0);
        }
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        holder.bind(msg);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        LinearLayout container;
        MaterialCardView cardBubble;
        TextView textRole;
        TextView textContent;
        ImageView imageMedia;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            container = (LinearLayout) itemView;
            cardBubble = itemView.findViewById(R.id.card_bubble);
            textRole = itemView.findViewById(R.id.text_role);
            textContent = itemView.findViewById(R.id.text_content);
            imageMedia = itemView.findViewById(R.id.image_media);
        }

        public void bind(ChatMessage msg) {
            textRole.setText(msg.getRole().name());
            textContent.setText(msg.getContent());

            if (msg.getMediaBytes() != null && msg.getMimeType() != null && msg.getMimeType().startsWith("image/")) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    opts.inSampleSize = 2;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(msg.getMediaBytes(), 0, msg.getMediaBytes().length, opts);
                    if (bitmap != null) {
                        imageMedia.setImageBitmap(bitmap);
                        imageMedia.setVisibility(View.VISIBLE);
                    } else {
                        imageMedia.setImageDrawable(null);
                        imageMedia.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    imageMedia.setImageDrawable(null);
                    imageMedia.setVisibility(View.GONE);
                }
            } else {
                imageMedia.setImageDrawable(null);
                imageMedia.setVisibility(View.GONE);
            }

            if (msg.getRole() == ChatMessage.Role.USER) {
                container.setGravity(Gravity.END);
                textRole.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_blue_dark));
            } else {
                container.setGravity(Gravity.START);
                textRole.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_green_dark));
            }
        }
    }
}
