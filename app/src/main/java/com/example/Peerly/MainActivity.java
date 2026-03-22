package com.example.Peerly;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "peerly_prefs";
    private static final String KEY_USERNAME = "username";

    private String username;
    private DatabaseReference presenceRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        username = prefs.getString(KEY_USERNAME, null);

        if (username == null || username.isEmpty()) {
            showUsernameDialog();
        } else {
            launchMain();
        }
    }

    private void showUsernameDialog() {
        setContentView(R.layout.activity_username);

        EditText usernameInput = findViewById(R.id.usernameInput);
        Button confirmBtn = findViewById(R.id.confirmUsernameBtn);
        ListView existingList = findViewById(R.id.existingUsersList);

        // Load existing online users for quick-pick
        DatabaseReference usersRef = FirebaseDatabase.getInstance()
                .getReference("presence");

        usersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Boolean online = child.child("online").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(online)) {
                        names.add(child.getKey());
                    }
                }
                if (!names.isEmpty()) {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            MainActivity.this,
                            android.R.layout.simple_list_item_1,
                            names);
                    existingList.setAdapter(adapter);
                    existingList.setOnItemClickListener((parent, view, pos, id) ->
                            usernameInput.setText(names.get(pos)));
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        });

        confirmBtn.setOnClickListener(v -> {
            String name = usernameInput.getText().toString().trim();
            if (name.isEmpty()) {
                usernameInput.setError("Enter a username");
                return;
            }
            // Sanitize: only alphanumeric + underscore
            if (!name.matches("[a-zA-Z0-9_]{2,20}")) {
                usernameInput.setError("2–20 chars, letters/numbers/underscore only");
                return;
            }
            saveUsernameAndLaunch(name);
        });
    }

    private void saveUsernameAndLaunch(String name) {
        username = name;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_USERNAME, username)
                .apply();
        launchMain();
    }

    private void launchMain() {
        setContentView(R.layout.activity_main);

        TextView userLabel = findViewById(R.id.userLabel);
        userLabel.setText("@" + username);

        // Register presence
        registerPresence();

        TabLayout tabs = findViewById(R.id.tabs);
        FrameLayout container = findViewById(R.id.fragmentContainer);

        // Load nearby chat by default
        switchToFragment(new NearbyChatFragment(), container);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    switchToFragment(new NearbyChatFragment(), container);
                } else {
                    switchToFragment(new PrivateRoomsFragment(), container);
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Change username button
        TextView changeUser = findViewById(R.id.changeUsername);
        changeUser.setOnClickListener(v -> {
            // Go offline, clear prefs, restart
            if (presenceRef != null) presenceRef.child("online").setValue(false);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_USERNAME).apply();
            username = null;
            showUsernameDialog();
        });
    }

    private void registerPresence() {
        presenceRef = FirebaseDatabase.getInstance()
                .getReference("presence")
                .child(username);

        Map<String, Object> presenceData = new HashMap<>();
        presenceData.put("online", true);
        presenceData.put("ts", ServerValue.TIMESTAMP);
        presenceRef.setValue(presenceData);

        // Auto-remove on disconnect
        presenceRef.child("online").onDisconnect().setValue(false);
    }

    private void switchToFragment(androidx.fragment.app.Fragment fragment, FrameLayout container) {
        Bundle args = new Bundle();
        args.putString("username", username);
        fragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (presenceRef != null) {
            presenceRef.child("online").setValue(false);
        }
    }

    public String getUsername() {
        return username;
    }
}
