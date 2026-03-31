package com.example.Peerly;

import android.os.Bundle;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.database.*;
import org.webrtc.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ChatActivity — true P2P WebRTC DataChannel chat.
 *
 * BUG FIXES vs original:
 *  1. ICE candidates are now split into /ice/caller and /ice/callee paths,
 *     so each peer only adds the OTHER peer's candidates (not their own).
 *  2. Null/length checks on ICE string parsing to prevent crashes.
 *  3. setLocalDescription is called with a real observer that logs failures.
 *  4. Room is marked inactive (not deleted) on disconnect, so history is preserved.
 *  5. DataChannel observer registered before signaling completes, avoiding race condition.
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "Peerly_WebRTC";

    private TextView chatBox, roomLabel;
    private EditText messageInput;
    private Button sendButton;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

    private DatabaseReference roomRef;
    private String roomId;
    private String username;
    private boolean isCaller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        chatBox = findViewById(R.id.chatBox);
        roomLabel = findViewById(R.id.roomLabel);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        TextView statusBar = findViewById(R.id.statusBar);
        TextView backBtn = findViewById(R.id.backBtn);

        backBtn.setOnClickListener(v -> onBackPressed());

        roomId = getIntent().getStringExtra("roomId");
        username = getIntent().getStringExtra("username");
        isCaller = getIntent().getBooleanExtra("isCaller", false);

        roomLabel.setText("Room: " + roomId);

        roomRef = FirebaseDatabase.getInstance()
                .getReference("rooms")
                .child(roomId);

        initWebRTC();

        if (isCaller) {
            startCaller();
        } else {
            listenForOffer();
        }

        sendButton.setOnClickListener(v -> sendMessage());

        // Copy room code to clipboard on tap
        roomLabel.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("room", roomId));
            Toast.makeText(this, "Room code copied!", Toast.LENGTH_SHORT).show();
        });
    }

    // ─── WebRTC Init ───────────────────────────────────────────────────────────

    private void initWebRTC() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(this)
                        .createInitializationOptions()
        );

        factory = PeerConnectionFactory.builder()
                .createPeerConnectionFactory();

        List<PeerConnection.IceServer> iceServers = Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                        .createIceServer(),
                // Second STUN for reliability
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                        .createIceServer()
        );

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                // FIX: write to our OWN side's path so the other peer can read it
                String myPath = isCaller ? "caller" : "callee";
                roomRef.child("ice").child(myPath).push().setValue(
                        candidate.sdpMid + "|" +
                                candidate.sdpMLineIndex + "|" +
                                candidate.sdp
                );
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.d(TAG, "ICE state: " + state);
                runOnUiThread(() -> {
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        chatBox.append("── Connected ──\n");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED
                            || state == PeerConnection.IceConnectionState.CLOSED
                            || state == PeerConnection.IceConnectionState.FAILED) {
                        chatBox.append("── Disconnected ──\n");
                        // Mark room inactive instead of deleting
                        roomRef.child("active").setValue(false);
                    }
                });
            }

            @Override
            public void onDataChannel(DataChannel channel) {
                // Callee receives the data channel here
                dataChannel = channel;
                setupDataChannel();
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] m) {}
        });
    }

    // ─── Caller Flow ───────────────────────────────────────────────────────────

    private void startCaller() {
        DataChannel.Init init = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel("chat", init);
        setupDataChannel();

        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Caller setLocalDescription failed: " + s);
                    }
                }, sdp);
                roomRef.child("offer").setValue(sdp.description);
                listenForAnswer();
                // FIX: caller listens to CALLEE's ice candidates
                listenForIce("callee");
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer failed: " + s);
            }
        }, new MediaConstraints());
    }

    // ─── Callee Flow ───────────────────────────────────────────────────────────

    private void listenForOffer() {
        roomRef.child("offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String offerSdp = snapshot.getValue(String.class);
                if (offerSdp == null) return;

                peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Callee setRemoteDescription failed: " + s);
                    }
                }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));

                peerConnection.createAnswer(new SdpObserverAdapter() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SdpObserverAdapter() {
                            @Override
                            public void onSetFailure(String s) {
                                Log.e(TAG, "Callee setLocalDescription failed: " + s);
                            }
                        }, sdp);
                        roomRef.child("answer").setValue(sdp.description);
                        // FIX: callee listens to CALLER's ice candidates
                        listenForIce("caller");
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.e(TAG, "createAnswer failed: " + s);
                    }
                }, new MediaConstraints());
            }

            @Override public void onCancelled(DatabaseError error) {
                Log.e(TAG, "listenForOffer cancelled: " + error.getMessage());
            }
        });
    }

    private void listenForAnswer() {
        roomRef.child("answer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String answerSdp = snapshot.getValue(String.class);
                if (answerSdp == null) return;
                peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Caller setRemoteDescription(answer) failed: " + s);
                    }
                }, new SessionDescription(SessionDescription.Type.ANSWER, answerSdp));
            }

            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    /**
     * @param side "caller" or "callee" — reads the OTHER peer's candidates
     */
    private void listenForIce(String side) {
        roomRef.child("ice").child(side).addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String s) {
                String value = snapshot.getValue(String.class);
                if (value == null) return;

                // FIX: safe split with length check
                String[] parts = value.split("\\|", 3);
                if (parts.length < 3) {
                    Log.e(TAG, "Malformed ICE candidate: " + value);
                    return;
                }

                try {
                    IceCandidate candidate = new IceCandidate(
                            parts[0],
                            Integer.parseInt(parts[1]),
                            parts[2]);
                    peerConnection.addIceCandidate(candidate);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "ICE parse error: " + e.getMessage());
                }
            }

            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    // ─── DataChannel ──────────────────────────────────────────────────────────

    private void setupDataChannel() {
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long prev) {}

            @Override
            public void onStateChange() {
                Log.d(TAG, "DataChannel state: " + dataChannel.state());
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    runOnUiThread(() -> {
                        sendButton.setEnabled(true);
                        chatBox.append("── Ready to chat ──\n");
                    });
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] bytes = new byte[buffer.data.remaining()];
                buffer.data.get(bytes);
                String message = new String(bytes, StandardCharsets.UTF_8);
                runOnUiThread(() -> chatBox.append("Peer: " + message + "\n"));
            }
        });
    }

    private void sendMessage() {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
            Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        dataChannel.send(new DataChannel.Buffer(buffer, false));
        chatBox.append("Me: " + message + "\n");
        messageInput.setText("");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Leave room?")
                .setMessage("You will disconnect from the P2P session.")
                .setPositiveButton("Leave", (d, w) -> {
                    cleanup();
                    super.onBackPressed();
                })
                .setNegativeButton("Stay", null)
                .show();
    }

    private void cleanup() {
        if (dataChannel != null) {
            dataChannel.close();
            dataChannel.dispose();
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
        }
        roomRef.child("active").setValue(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }
}
