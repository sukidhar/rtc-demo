package com.example.groupcall;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONException;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;

import java.util.List;

public class MainActivity extends Activity implements WebRTCClient.PeerListener{
    private static final String VideoCodec = "VP9";
    private static final String AudioCodec = "opus";
    private final static int VideoCallSent = 666;
    PermissionChecker permissionChecker = new PermissionChecker();
    private static final String[] RequiredPermissions = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private String socketAddress;
    private WebRTCClient client;
    private GLSurfaceView surfaceView;
    private String callerId;
    private VideoRenderer.Callbacks localRender;
    private VideoRenderer.Callbacks remoteRender;
    private final VideoRendererGui.ScalingType scalingType = VideoRendererGui.ScalingType.SCALE_ASPECT_FILL;

    // Local preview screen position before call is connected.
    private static final int LOCAL_X_CONNECTING = 0;
    private static final int LOCAL_Y_CONNECTING = 0;
    private static final int LOCAL_WIDTH_CONNECTING = 100;
    private static final int LOCAL_HEIGHT_CONNECTING = 100;
    // Local preview screen position after call is connected.
    private static final int LOCAL_X_CONNECTED = 72;
    private static final int LOCAL_Y_CONNECTED = 72;
    private static final int LOCAL_WIDTH_CONNECTED = 25;
    private static final int LOCAL_HEIGHT_CONNECTED = 25;
    // Remote video screen position
    private static final int REMOTE_X = 0;
    private static final int REMOTE_Y = 0;
    private static final int REMOTE_WIDTH = 100;
    private static final int REMOTE_HEIGHT = 100;


    private void renderInit(){
        int height = Resources.getSystem().getDisplayMetrics().heightPixels-100;
        int width = Resources.getSystem().getDisplayMetrics().widthPixels-50;
        P2PConnectionParameters parameters = new P2PConnectionParameters(true,false,width,height,30,1,VideoCodec,true,1,AudioCodec,true);
        //instantiate the client class and refer this class as observer
        client = new WebRTCClient(this,socketAddress,parameters,VideoRendererGui.getEGLContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        surfaceView.onResume();
        if (client != null){
            client.OnResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        surfaceView.onPause();
        if (client != null){
            client.OnPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (client != null){
            client.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void receiveCallerId(String id) {
        this.callerId = id;
        System.out.println("answering");
        this.onCallReady(callerId);
    }

    @Override
    public void onCallReady(String callId) {
        if (callerId != null) {
            try {
                answer(this.callerId);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        } else {
            call(callId);
        }
    }

    public void answer(String callerId) throws JSONException {
        client.sendMessage(callerId, "init", null);
        startCamera();
    }

    public void call(String callId) {
        Intent msg = new Intent(Intent.ACTION_SEND);
        msg.putExtra(Intent.EXTRA_TEXT, socketAddress +"/"+ callId);
        msg.setType("text/plain");
        startActivityForResult(Intent.createChooser(msg, "Call someone :"), VideoCallSent);
    }

    @Override
    public void onStatusChanged(String newStatus) {
        runOnUiThread(()-> Toast.makeText(getApplicationContext(), newStatus, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onLocalStream(MediaStream localStream) {
        localStream.videoTracks.get(0).addRenderer(new VideoRenderer(localRender));
        VideoRendererGui.update(localRender,
                LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
                LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING,
                scalingType, false);
    }

    @Override
    public void onAddRemoteStream(MediaStream remoteStream, int endPoint) {
        remoteStream.videoTracks.get(0).addRenderer(new VideoRenderer(remoteRender));
        VideoRendererGui.update(remoteRender,
                REMOTE_X, REMOTE_Y,
                REMOTE_WIDTH, REMOTE_HEIGHT, scalingType, false);
        VideoRendererGui.update(localRender,
                LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED,
                LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED,
                scalingType, false);
    }

    @Override
    public void onRemoveRemoteStream(int endPoint) {
        VideoRendererGui.update(localRender,
                LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
                LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING,
                scalingType, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setContentView(R.layout.activity_main);
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null)
                controller.hide(WindowInsets.Type.statusBars());
        }
        else {
            //noinspection deprecation
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            setContentView(R.layout.activity_main);
        }

        socketAddress = "http://706e4452002a.ngrok.io";

        surfaceView = findViewById(R.id.glview_call);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setKeepScreenOn(true);
        VideoRendererGui.setView(surfaceView,()->{renderInit();});

        remoteRender = VideoRendererGui.create(REMOTE_X,REMOTE_Y,REMOTE_WIDTH,REMOTE_HEIGHT,scalingType,false);
        localRender = VideoRendererGui.create(LOCAL_X_CONNECTING,LOCAL_Y_CONNECTING,LOCAL_WIDTH_CONNECTING,LOCAL_HEIGHT_CONNECTING,scalingType,true);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)){
            final List<String> segments = intent.getData().getPathSegments();
            callerId = segments.get(0);
        }
        callerId = "gNqXWuJXhkyrnM16AAAx";
        checkPermissions();
    }

    public void startCamera(){
        if (PermissionChecker.hasPermissions(this,RequiredPermissions)){
            client.start("android_test");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VideoCallSent) {
            startCamera();
        }
    }

    private void checkPermissions(){
        permissionChecker.verifyPermissions(this, RequiredPermissions, new PermissionChecker.VerifyPermissionsCallback() {
            @Override
            public void onPermissionAllGranted() {
                return;
            }

            @Override
            public void onPermissionDeny(String[] permissions) {
                Toast.makeText(getApplicationContext(),"Please grant necessary Permissions", Toast.LENGTH_SHORT);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionChecker.onRequestPermissionsResult(requestCode,permissions,grantResults);
    }


}