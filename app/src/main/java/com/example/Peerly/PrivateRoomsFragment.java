package com.example.Peerly;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.firebase.database.*;
import java.util.*;

/**
 * PrivateRoomsFragment
 *
 * Shows a list of active private rooms. Users can:
 *  - Create a new room (gets a short readable code like "tiger-42")
 *  - Join an existing room by tapping it from the list
 *  - Enter a room code manually
 *
 * Each room entry in Firebase: /rooms/{roomId} → { name, createdBy, createdAt, active }
 */
public class PrivateRoomsFragment extends Fragment {

    private EditText joinRoomInput;
    private Button joinBtn, createBtn;
    private ListView roomList;
    private TextView emptyLabel;

    private String username;
    private DatabaseReference roomsRef;
    private ValueEventListener roomsListener;

    private final List<String> roomIds = new ArrayList<>();
    private final List<RoomListAdapter.RoomItem> roomItems = new ArrayList<>();
    private RoomListAdapter adapter;

    // Adjectives + nouns for human-readable room codes
    private static final String[] ADJECTIVES = {
            "swift", "calm", "bold", "bright", "silent",
            "green", "amber", "keen", "wild", "cool"
    };
    private static final String[] NOUNS = {
            "tiger", "river", "cloud", "stone", "flame",
            "cedar", "orbit", "spark", "drift", "lunar"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_private_rooms, container, false);

        joinRoomInput = view.findViewById(R.id.joinRoomInput);
        joinBtn = view.findViewById(R.id.joinRoomBtn);
        createBtn = view.findViewById(R.id.createRoomBtn);
        roomList = view.findViewById(R.id.roomList);
        emptyLabel = view.findViewById(R.id.emptyRoomsLabel);

        username = getArguments() != null ? getArguments().getString("username") : "Anonymous";

        roomsRef = FirebaseDatabase.getInstance().getReference("rooms");

        adapter = new RoomListAdapter(requireContext(), roomItems);
        roomList.setAdapter(adapter);

        loadRooms();

        createBtn.setOnClickListener(v -> createRoom());

        joinBtn.setOnClickListener(v -> {
            String code = joinRoomInput.getText().toString().trim().toLowerCase();
            if (code.isEmpty()) {
                joinRoomInput.setError("Enter a room code");
                return;
            }
            openChatRoom(code, false);
        });

        roomList.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos < roomIds.size()) {
                openChatRoom(roomIds.get(pos), false);
            }
        });

        return view;
    }

    private void loadRooms() {
        roomsListener = roomsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                roomIds.clear();
                roomItems.clear();

                for (DataSnapshot room : snapshot.getChildren()) {
                    Boolean active = room.child("active").getValue(Boolean.class);
                    if (!Boolean.TRUE.equals(active)) continue;

                    String roomId = room.getKey();
                    String createdBy = room.child("createdBy").getValue(String.class);
                    if (createdBy == null) createdBy = "unknown";
                    roomIds.add(roomId);
                    roomItems.add(new RoomListAdapter.RoomItem(roomId, createdBy, "open"));
                }

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        emptyLabel.setVisibility(roomIds.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                }
            }

            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void createRoom() {
        String roomId = generateRoomCode();

        Map<String, Object> roomData = new HashMap<>();
        roomData.put("createdBy", username);
        roomData.put("active", true);
        roomData.put("createdAt", ServerValue.TIMESTAMP);

        roomsRef.child(roomId).setValue(roomData, (error, ref) -> {
            if (error == null) {
                openChatRoom(roomId, true);
            } else {
                Toast.makeText(getContext(), "Failed to create room", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String generateRoomCode() {
        Random rng = new Random();
        String adj = ADJECTIVES[rng.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[rng.nextInt(NOUNS.length)];
        int num = rng.nextInt(90) + 10; // 10–99
        return adj + "-" + noun + "-" + num;
    }

    private void openChatRoom(String roomId, boolean isCaller) {
        Intent intent = new Intent(getActivity(), ChatActivity.class);
        intent.putExtra("roomId", roomId);
        intent.putExtra("username", username);
        intent.putExtra("isCaller", isCaller);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (roomsListener != null) roomsRef.removeEventListener(roomsListener);
    }
}
