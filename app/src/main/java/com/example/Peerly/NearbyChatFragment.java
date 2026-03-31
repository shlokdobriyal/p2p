package com.example.Peerly;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * NearbyChatFragment
 *
 * A shared "world" chat channel where all online users can talk.
 * Messages are stored in /nearby_chat and expire after 24h via Firebase TTL rules
 * (set in your Firebase Realtime DB rules — see README).
 *
 * No WebRTC is needed here — this is a simple broadcast channel,
 * which is fine because the point of P2P (WebRTC) is private 1:1 rooms.
 */
public class NearbyChatFragment extends Fragment {

    private TextView chatBox;
    private EditText messageInput;
    private Button sendButton;
    private TextView onlineCount;

    private String username;
    private DatabaseReference chatRef;
    private DatabaseReference presenceRef;
    private ChildEventListener chatListener;
    private ValueEventListener presenceListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_nearby_chat, container, false);

        chatBox = view.findViewById(R.id.nearbyChatBox);
        messageInput = view.findViewById(R.id.nearbyMessageInput);
        sendButton = view.findViewById(R.id.nearbySendBtn);
        onlineCount = view.findViewById(R.id.onlineCount);

        username = getArguments() != null ? getArguments().getString("username") : "Anonymous";

        chatRef = FirebaseDatabase.getInstance().getReference("nearby_chat");
        presenceRef = FirebaseDatabase.getInstance().getReference("presence");

        listenForMessages();
        listenForPresence();

        sendButton.setOnClickListener(v -> sendMessage());

        return view;
    }

    private void listenForMessages() {
        // Only load last 100 messages
        chatListener = chatRef.limitToLast(100)
                .addChildEventListener(new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot snapshot, String prevKey) {
                        String from = snapshot.child("from").getValue(String.class);
                        String text = snapshot.child("text").getValue(String.class);
                        Long ts = snapshot.child("ts").getValue(Long.class);

                        if (from == null || text == null) return;

                        String time = ts != null
                                ? new SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(new Date(ts))
                                : "";

                        String line = String.format("[%s] %s: %s\n", time, from, text);

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> chatBox.append(line));
                        }
                    }

                    @Override public void onChildChanged(DataSnapshot s, String p) {}
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void listenForPresence() {
        presenceListener = presenceRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                int count = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    Boolean online = child.child("online").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(online)) count++;
                }
                int finalCount = count;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            onlineCount.setText(finalCount + " online"));
                }
            }

            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("from", username);
        msg.put("text", text);
        msg.put("ts", ServerValue.TIMESTAMP);

        chatRef.push().setValue(msg);
        messageInput.setText("");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (chatListener != null) chatRef.removeEventListener(chatListener);
        if (presenceListener != null) presenceRef.removeEventListener(presenceListener);
    }
}
