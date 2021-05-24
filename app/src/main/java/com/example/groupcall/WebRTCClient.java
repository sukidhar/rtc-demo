package com.example.groupcall;


import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoSource;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;

import android.opengl.EGLContext;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class WebRTCClient {
    private final static int MaxPeers = 2;
    private final static String Tag = WebRTCClient.class.getCanonicalName();
    private PeerConnectionFactory factory;
    private boolean[] endPoints = new boolean[MaxPeers];
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private HashMap<String, Peer> peers = new HashMap<>();
    private MediaConstraints peerMediaConstraints = new MediaConstraints();
    private MediaStream localMediaStream;
    private VideoSource videoSource;
    private PeerListener peerListener;
    private P2PConnectionParameters connectionParameters;
    private Socket client;


    //Interfaces

    private interface Command {
        void execute(String peerId, JSONObject payload) throws JSONException;
    }
    public interface PeerListener {
        void receiveCallerId(String id);

        void onCallReady(String callId);

        void onStatusChanged(String newStatus);

        void onLocalStream(MediaStream localStream);

        void onAddRemoteStream(MediaStream remoteStream, int endPoint);

        void onRemoveRemoteStream(int endPoint);
    }

    public void askCallerId(){
        if (client != null){
            client.emit("needCallerId","");
        }
    }


    // socket holder and current class constructor

    public WebRTCClient(PeerListener peerListener, String host, P2PConnectionParameters parameters, EGLContext eglContext){
        this.peerListener = peerListener;
        this.connectionParameters = parameters;
        PeerConnectionFactory.initializeAndroidGlobals(peerListener,true,true,parameters.videoCodecHwAcceleration,eglContext);
        factory = new PeerConnectionFactory();
        SignalingServerMessageHandler messageHandler = new SignalingServerMessageHandler();
        try {
            client = IO.socket(host);
        }catch (URISyntaxException error){
            error.printStackTrace();
        }
        client.on("takeCallerId",messageHandler.onTakeCallerId);
        client.on("id",messageHandler.onId);
        client.on("message",messageHandler.onCommand);
        client.on("hello", new Emitter.Listener() {
            @Override
            public void call(Object... args) {

            }
        });
        client.connect();

        iceServers.add(new PeerConnection.IceServer("stun:23.21.150.121"));
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));

        peerMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerMediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
    }

    private Peer addPeer(String id, int endPoint) {
        Peer peer = new Peer(id, endPoint);
        peers.put(id, peer);
        endPoints[endPoint] = true;
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        peerListener.onRemoveRemoteStream(peer.endPoint);
        peer.peerConnection.close();
        peers.remove(peer.id);
        endPoints[peer.endPoint] = false;
    }

    private int findEndPoint() {
        for (int i = 0; i < MaxPeers; i++) if (!endPoints[i]) return i;
        return MaxPeers;
    }

    public void sendMessage(String to, String type, JSONObject payload) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("to", to);
        message.put("type", type);
        message.put("payload", payload);
        System.out.println("payload for message :  " +payload);
        client.emit("message", message);
    }

    public void OnResume(){
        if (videoSource != null){
            videoSource.restart();
        }
    }

    public void OnPause(){
        if (videoSource != null){
            videoSource.stop();
        }
    }

    public void onDestroy() {
        for (Peer peer : peers.values()) {
            peer.peerConnection.dispose();
        }
        if (videoSource != null) {
            videoSource.dispose();
        }
        factory.dispose();
        client.off();
        client.disconnect();
        client.close();
    }

    public void start(String name) {
        setCamera();
        try {
            JSONObject message = new JSONObject();
            message.put("name", name);
            client.emit("readyToStream", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void setCamera() {
        localMediaStream = factory.createLocalMediaStream("ARDAMS");
        if (connectionParameters.videoCallEnabled) {
            MediaConstraints videoConstraints = new MediaConstraints();
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(connectionParameters.videoHeight)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(connectionParameters.videoWidth)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(connectionParameters.videoFps)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", Integer.toString(connectionParameters.videoFps)));
            videoSource = factory.createVideoSource(getVideoCapture(), videoConstraints);
            localMediaStream.addTrack(factory.createVideoTrack("ARDAMSv0", videoSource));
        }
        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        localMediaStream.addTrack(factory.createAudioTrack("ARDAMSa0", audioSource));
        peerListener.onLocalStream(localMediaStream);
    }

    private VideoCapturer getVideoCapture() {
        String frontCameraDeviceName = VideoCapturerAndroid.getNameOfFrontFacingDevice();
        return VideoCapturerAndroid.create(frontCameraDeviceName);
    }

    private class SignalingServerMessageHandler{
        private HashMap<String,Command> commandMap;

        private SignalingServerMessageHandler(){
            this.commandMap = new HashMap<>();
            commandMap.put("init",new CreateCallOfferCommand());
            commandMap.put("offer",new CreateAnswerCommand());
            commandMap.put("answer",new SetRemoteSDPCommand());
            commandMap.put("candidate", new AddIceCandidateCommand());
        }

        public Emitter.Listener onCommand = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JSONObject data = (JSONObject) args[0];
                try {
                    String from = data.getString("from");
                    String type = data.getString("type");
                    JSONObject payload = null;
                    if (!type.equals("init")) {
                        payload = data.getJSONObject("payload");
                    }
                    // if peer is unknown, try to add him
                    if (!peers.containsKey(from)) {
                        // if MAX_PEER is reach, ignore the call
                        int endPoint = findEndPoint();
                        if (endPoint != MaxPeers) {
                            Peer peer = addPeer(from, endPoint);
                            peer.peerConnection.addStream(localMediaStream);
                            commandMap.get(type).execute(from, payload);
                        }
                    } else {
                        commandMap.get(type).execute(from, payload);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        public Emitter.Listener onTakeCallerId = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                String id = (String) args[0];
                System.out.println("caller id :" + id);
                peerListener.receiveCallerId(id);
            }
        };

        public Emitter.Listener onId = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                System.out.println("ID Received");
                String id = (String) args[0];
                peerListener.onCallReady(id);
            }
        };
    }

    private class CreateCallOfferCommand implements Command{
        @Override
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(Tag,"Create Call Offer Command Executed");
            Peer peer = peers.get(peerId);
            peer.peerConnection.createOffer(peer,peerMediaConstraints);
        }
    }

    private class CreateAnswerCommand implements Command{
        @Override
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(Tag,"Create Answer Command Executed");
            Peer peer = peers.get(peerId);
            SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.fromCanonicalForm(payload.getString("type")),payload.getString("sdp"));
            peer.peerConnection.setRemoteDescription(peer,sessionDescription);
            peer.peerConnection.createAnswer(peer,peerMediaConstraints);
        }
    }

    private class SetRemoteSDPCommand implements Command{
        @Override
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(Tag,"Create Remote SDP Command Executed");
            Peer peer = peers.get(peerId);
            SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.fromCanonicalForm(payload.getString("type")),payload.getString("sdp"));
            peer.peerConnection.setRemoteDescription(peer,sessionDescription);
        }
    }

    private class AddIceCandidateCommand implements Command{
        @Override
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(Tag, "Add Ice Candidate Command Executed");
            PeerConnection pConnection = peers.get(peerId).peerConnection;
            if (pConnection.getRemoteDescription() != null){
                IceCandidate iceCandidate = new IceCandidate(
                        payload.getString("id"),
                        payload.getInt("label"),
                        payload.getString("candidate")
                );
                pConnection.addIceCandidate(iceCandidate);
            }
        }
    }

    private class Peer implements SdpObserver, PeerConnection.Observer{
        private PeerConnection peerConnection;
        private String id;
        private int endPoint;


        public Peer(String id, int endPoint){
            Log.d(Tag, "new Peer: " + id + ", from : " + endPoint);
            this.peerConnection = factory.createPeerConnection(iceServers, peerMediaConstraints,this);
            this.id = id;
            this.endPoint = endPoint;

            //initialise local stream

            peerConnection.addStream(localMediaStream);
            peerListener.onStatusChanged("CONNECTING");
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED){
                removePeer(id);
                peerListener.onStatusChanged("DISCONNECTED");
            }
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            try{
                JSONObject payload = new JSONObject();
                payload.put("label", iceCandidate.sdpMLineIndex);
                payload.put("id", iceCandidate.sdpMid);
                payload.put("candidate", iceCandidate.sdp);
                sendMessage(id,"candidate",payload);
            }catch (JSONException error){
                error.printStackTrace();
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(Tag, "On Add Stream "+mediaStream.label());
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
            peerListener.onAddRemoteStream(mediaStream,endPoint+1);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(Tag, "On Remove Stream " + mediaStream.label());
            removePeer(id);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            try{
                JSONObject payload = new JSONObject();
                payload.put("sdp",sessionDescription);
                payload.put("type",sessionDescription.type.canonicalForm());
                sendMessage(id,sessionDescription.type.canonicalForm(),payload);
                peerConnection.setLocalDescription(Peer.this,sessionDescription);
            }catch (JSONException error){
                error.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }
    }
}
