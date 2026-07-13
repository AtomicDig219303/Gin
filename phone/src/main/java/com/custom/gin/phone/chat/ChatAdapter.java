package com.custom.gin.phone.chat;

import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.custom.gin.phone.R;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
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
                imageMedia.setVisibility(View.VISIBLE);
                try {
                    android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(msg.getMediaBytes(), 0, msg.getMediaBytes().length);
                    imageMedia.setImageBitmap(bitmap);
                } catch (Exception e) {
                    imageMedia.setVisibility(View.GONE);
                }
            } else {
                imageMedia.setVisibility(View.GONE);
            }

            if (msg.getRole() == ChatMessage.Role.USER) {
                container.setGravity(Gravity.END);
                textRole.setTextColor(itemView.getContext().getResources().getColor(android.R.color.holo_blue_dark));
            } else {
                container.setGravity(Gravity.START);
                textRole.setTextColor(itemView.getContext().getResources().getColor(android.R.color.holo_green_dark));
            }
        }
    }
}
