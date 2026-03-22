package com.example.p2pm;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.*;

import org.webrtc.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebRTC";

    private EditText roomInput, messageInput;
    private Button startButton, sendButton;
    private TextView chatBox;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

    private DatabaseReference roomRef;
    private boolean isCaller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        roomInput = findViewById(R.id.roomInput);
        startButton = findViewById(R.id.startButton);
        chatBox = findViewById(R.id.chatBox);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        initWebRTC();

        startButton.setOnClickListener(v -> startConnection());
        sendButton.setOnClickListener(v -> sendMessage());
    }

    private void startConnection() {

        String roomId = roomInput.getText().toString().trim();
        FirebaseDatabase database = FirebaseDatabase.getInstance();

        if (roomId.isEmpty()) {
            isCaller = true;
            roomRef = database.getReference("rooms").push();
            chatBox.append("Created Room: " + roomRef.getKey() + "\n");
            startCaller();
        } else {
            isCaller = false;
            roomRef = database.getReference("rooms").child(roomId);
            chatBox.append("Joined Room: " + roomId + "\n");
            listenForOffer();
        }
    }

    private void initWebRTC() {

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions()
        );

        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                        .createIceServer()
        );

        peerConnection = factory.createPeerConnection(
                new PeerConnection.RTCConfiguration(iceServers),
                new PeerConnection.Observer() {

                    @Override
                    public void onIceCandidate(IceCandidate candidate) {
                        if (roomRef != null)
                            roomRef.child("ice").push().setValue(
                                    candidate.sdpMid + "|" +
                                            candidate.sdpMLineIndex + "|" +
                                            candidate.sdp
                            );
                    }

                    @Override
                    public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                        if (state == PeerConnection.IceConnectionState.DISCONNECTED
                                || state == PeerConnection.IceConnectionState.CLOSED) {
                            if (roomRef != null) {
                                roomRef.removeValue();
                                Log.d(TAG, "Room auto deleted");
                            }
                        }
                    }

                    @Override
                    public void onDataChannel(DataChannel channel) {
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
                }
        );
    }

    private void startCaller() {

        DataChannel.Init init = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel("chat", init);
        setupDataChannel();

        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserverAdapter(), sdp);
                roomRef.child("offer").setValue(sdp.description);
                listenForAnswer();
                listenForIce();
            }
        }, new MediaConstraints());
    }

    private void listenForOffer() {

        roomRef.child("offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String offer = snapshot.getValue(String.class);

                SessionDescription offerSdp =
                        new SessionDescription(SessionDescription.Type.OFFER, offer);

                peerConnection.setRemoteDescription(new SdpObserverAdapter(), offerSdp);

                peerConnection.createAnswer(new SdpObserverAdapter() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SdpObserverAdapter(), sdp);
                        roomRef.child("answer").setValue(sdp.description);
                        listenForIce();
                    }
                }, new MediaConstraints());
            }

            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void listenForAnswer() {

        roomRef.child("answer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String answer = snapshot.getValue(String.class);

                SessionDescription sdp =
                        new SessionDescription(SessionDescription.Type.ANSWER, answer);

                peerConnection.setRemoteDescription(new SdpObserverAdapter(), sdp);
            }

            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void listenForIce() {

        roomRef.child("ice").addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String s) {

                String value = snapshot.getValue(String.class);
                if (value == null) return;

                String[] parts = value.split("\\|");

                IceCandidate candidate =
                        new IceCandidate(parts[0],
                                Integer.parseInt(parts[1]),
                                parts[2]);

                peerConnection.addIceCandidate(candidate);
            }

            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void setupDataChannel() {

        dataChannel.registerObserver(new DataChannel.Observer() {

            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                Log.d(TAG, "Channel state: " + dataChannel.state());
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {

                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                String message = new String(bytes, StandardCharsets.UTF_8);

                runOnUiThread(() ->
                        chatBox.append("Peer: " + message + "\n"));
            }
        });
    }

    private void sendMessage() {

        if (dataChannel == null ||
                dataChannel.state() != DataChannel.State.OPEN) {
            Log.d(TAG, "Channel not open yet ❌");
            return;
        }

        String message = messageInput.getText().toString();
        if (message.isEmpty()) return;

        ByteBuffer buffer =
                ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

        dataChannel.send(new DataChannel.Buffer(buffer, false));

        chatBox.append("Me: " + message + "\n");
        messageInput.setText("");
    }
}