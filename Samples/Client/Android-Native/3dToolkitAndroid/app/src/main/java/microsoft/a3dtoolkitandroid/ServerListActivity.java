package microsoft.a3dtoolkitandroid;


import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.facebook.stetho.Stetho;
import com.facebook.stetho.okhttp3.StethoInterceptor;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import microsoft.a3dtoolkitandroid.util.CustomStringRequest;
import microsoft.a3dtoolkitandroid.util.EglBase;
import microsoft.a3dtoolkitandroid.util.OkHttpStack;
import microsoft.a3dtoolkitandroid.util.SurfaceViewRenderer;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import static java.lang.Integer.parseInt;

public class ServerListActivity extends AppCompatActivity {

    public static final String SERVER_NAME = "com.microsoft.a3dtoolkitandroid.SERVER_NAME";
    private static final String LOG = "ServerListLog";
    private static final String ERROR = "ServerListLogError";
    private final int heartbeatIntervalInMs = 5000;
    private Intent intent;
    private RequestQueue requestQueue;
    private HashMap<Integer, String> otherPeers = new HashMap<>();
    private List<String> peers;
    private int myID;
    private String server;
    private String port;
    private int messageCounter = 0;
    private PeerConnection pc;
    private PeerConnectionFactory peerConnectionFactory;
    private DataChannel inputChannel;
    private ArrayAdapter adapter;
    private VideoTrack remoteVideoTrack;
    private SurfaceViewRenderer fullscreenRenderer;
    private final EglBase rootEglBase = EglBase.create();

    //alert dialog
    private AlertDialog.Builder builder;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Stetho.initializeWithDefaults(this);
        setContentView(R.layout.activity_server_list);
        final ListView listview = (ListView) findViewById(R.id.ServerListView);

        //create surface renderer and initialize it
        fullscreenRenderer = (SurfaceViewRenderer) findViewById(R.id.fullscreen_video_view);
        fullscreenRenderer.init(rootEglBase.getEglBaseContext(), null);

        intent = getIntent();
        server = intent.getStringExtra(ConnectActivity.SERVER_SERVER);
        port = intent.getStringExtra(ConnectActivity.SERVER_PORT);

        final Intent nextIntent = new Intent(this, StreamActivity.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            builder = new AlertDialog.Builder(this);
        }

        // Initialize list from sign in response
        String response = intent.getStringExtra(ConnectActivity.SERVER_LIST);
        peers = new ArrayList<>(Arrays.asList(response.split("\n")));
        myID = parseInt(peers.remove(0).split(",")[1]);
        Log.d(LOG, "My ID: " + myID);
        for (int i = 0; i < peers.size(); ++i) {
            if (peers.get(i).length() > 0) {
                Log.d(LOG, "Peer " + i + ": " + peers.get(i));
                String[] parsed = peers.get(i).split(",");
                otherPeers.put(parseInt(parsed[1]), parsed[0]);
            }
        }
        adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, peers);
        listview.setAdapter(adapter);

        startHangingGet();
        startHeartBeat();
        updatePeerList();

        // creates a click listener for the peer list
        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, final View view, int position, long id) {
                String item = (String) parent.getItemAtPosition(position);
                Log.d(LOG, "onItemClick: item = " + item);
                String serverName = item.split(",")[0].trim();
                Log.d(LOG, "onItemClick: serverName = " + serverName);
                for (Map.Entry<Integer, String> serverEntry : otherPeers.entrySet()){
                    if(serverEntry.getValue().equals(serverName)){
                        Log.d(LOG, "onItemClick: PeerID = " + serverEntry.getKey());
                        fullscreenRenderer.setVisibility(View.VISIBLE);
                        joinPeer(serverEntry.getKey());
                    }
                }
            }
        });

//        //show the alert
//        AlertDialog alertDialog = builder.create();
//        alertDialog.show();
    }

    /**
     * Joins server selected from list of peers
     * @param peerId: Choosen peerID to connect to
     */
    private void joinPeer(final int peerId) {
        Log.d(LOG, "joinPeer: ");

        createPeerConnection(peerId);

        inputChannel = pc.createDataChannel("inputDataChannel", new DataChannel.Init());
        inputChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {

            }

            @Override
            public void onStateChange() {

            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {

            }
        });

        MediaConstraints offerOptions = new MediaConstraints();
        offerOptions.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        offerOptions.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        pc.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(final SessionDescription sessionDescription) {
                Log.d(LOG, "joinPeer: onCreateSuccess1");

                final SessionDescription sessionDescriptionH264 = new SessionDescription(sessionDescription.type, preferCodec(sessionDescription.description, "H264" , false));
                pc.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        Log.d(LOG, "joinPeer: onCreateSuccess2");
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(LOG, "joinPeer: onSetSuccess2");
                        HashMap<String, String> params = new HashMap<>();
                        params.put("type", "offer");
                        params.put("sdp", sessionDescriptionH264.description);
                        sendToPeer(peerId, params);
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.d(LOG, "joinPeer: onCreateFailure2: " + s);

                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.d(LOG, "joinPeer: onSetFailure2: " + s);

                    }
                }, sessionDescriptionH264);
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
        }, offerOptions);

    }

    /**
     * Creates a peer connection using the ICE server and media constraints specified
     * @param peer_id (int): server ID
     */
    private void createPeerConnection(int peer_id) {
        try {
            Log.d(LOG, "createPeerConnection: ");
            MediaConstraints defaultPeerConnectionConstraints = new MediaConstraints();
            defaultPeerConnectionConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

            PeerConnection.IceServer iceServer = new PeerConnection.IceServer("turn:turnserver3dstreaming.centralus.cloudapp.azure.com:5349", "user", "3Dtoolkit072017", PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK);
            List<PeerConnection.IceServer> iceServerList = new ArrayList<>();
            iceServerList.add(iceServer);

            PeerConnection.Observer peerConnectionObserver = new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                    Log.d(LOG, "createPeerConnection: onSignalingChange");
                }

                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                    Log.d(LOG, "createPeerConnection: onIceConnectionChange");

                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {
                    Log.d(LOG, "createPeerConnection: onIceConnectionReceivingChange");

                }

                @Override
                public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                    Log.d(LOG, "createPeerConnection: onIceGatheringChange");

                }

                @Override
                public void onIceCandidate(IceCandidate iceCandidate) {
                    Log.d(LOG, "createPeerConnection: onIceCandidate");
                    HashMap<String, String> params = new HashMap<>();
                    params.put("sdpMLineIndex", String.valueOf(iceCandidate.sdpMLineIndex));
                    params.put("sdpMid", iceCandidate.sdpMid);
                    params.put("candidate", iceCandidate.sdp);
                    sendToPeer(peer_id, params);
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                    Log.d(LOG, "createPeerConnection: onIceCandidatesRemoved");

                }

                @Override
                public void onAddStream(MediaStream mediaStream) {
                    Log.d(LOG, "createPeerConnection: onAddStream = " + mediaStream.toString());
                    if (pc == null) {
                        return;
                    }
                    if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                        Log.d(ERROR, "Weird-looking stream: " + mediaStream);
                        return;
                    }
                    if (mediaStream.videoTracks.size() == 1) {
                        remoteVideoTrack = mediaStream.videoTracks.get(0);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    remoteVideoTrack.setEnabled(true);
                                    remoteVideoTrack.addRenderer(new VideoRenderer(fullscreenRenderer));
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                    }
                }

                @Override
                public void onRemoveStream(MediaStream mediaStream) {
                    Log.d(LOG, "createPeerConnection: onRemoveStream");

                }

                @Override
                public void onDataChannel(DataChannel dataChannel) {
                    Log.d(LOG, "createPeerConnection: onDataChannel");
                    inputChannel = dataChannel;

                }

                @Override
                public void onRenegotiationNeeded() {
                    Log.d(LOG, "createPeerConnection: onRenegotiationNeeded");

                }

                @Override
                public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                    Log.d(LOG, "createPeerConnection: onAddTrack");

                }
            };

            //Initialize PeerConnectionFactory globals.
            //Params are context, initAudio, initVideo and videoCodecHwAcceleration
            PeerConnectionFactory.initializeAndroidGlobals(this, false, true, true);
            peerConnectionFactory = new PeerConnectionFactory();

            pc = peerConnectionFactory.createPeerConnection(iceServerList, defaultPeerConnectionConstraints, peerConnectionObserver);
            Log.d(LOG, "createPeerConnection: PeerConnection = " + pc.toString());

        } catch (Throwable error) {
            Log.d(ERROR, "Failed to create PeerConnection, exception: " + error.toString());
        }
    }

    /**
     * Sends sdp offer to server
     * @param peer_id (int): server ID
     * @param params (HashMap<String, String></String,>): json post data
     */
    private void sendToPeer(int peer_id, HashMap<String, String> params) {
        try {
            Log.d(LOG, "sendToPeer(): " + peer_id + " Send " + params.toString());
            if (myID == -1) {
                Log.d(ERROR, "sendToPeer: Not Connected");
                return;
            }
            if (peer_id == myID) {
                Log.d(ERROR, "sendToPeer: Can't send a message to oneself :)");
                return;
            }


            JsonObjectRequest getRequest = new JsonObjectRequest(server + "/message?peer_id=" + myID + "&to=" + peer_id, new JSONObject(params),
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(LOG, "sendToPeer(): Response = " + response.toString());
                            //Process os success response
                        }
                    }, new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.d(ERROR, "onErrorResponse: SendToPeer = " + error);
                        }
                    }) {
                        @Override
                        public Map<String, String> getHeaders() throws AuthFailureError {
                            Map<String, String> headerParams = new HashMap<>();
                            headerParams.put("Peer-Type", "Client");
                            return headerParams;
                        }
                        @Override
                        public String getBodyContentType() {
                            return "text/plain";
                        }
                    };

            // Add the request to the RequestQueue.
            getVolleyRequestQueue().add(getRequest);
        } catch (Throwable e) {
            Log.d(ERROR, "send to peer error: " + e.toString());
        }
    }

    /**
     * Handles messages from the server (offer, answer, adding ICE candidate).
     * @param peer_id: id of peer
     * @param data: JSON response from server
     * @throws JSONException
     */
    private void handlePeerMessage(final int peer_id, JSONObject data) throws JSONException {
        Log.d(LOG, "handlePeerMessage: START");
        messageCounter++;
        Log.d(LOG, "handlePeerMessage: Message from '" + otherPeers.get(peer_id) + ":" + data);

        // observer for local callback functions
        final SdpObserver localObsever = new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(LOG, "handlePeerMessage:  localObsever onCreateSuccess " + sessionDescription.description);

            }

            @Override
            public void onSetSuccess() {
                Log.d(LOG, "handlePeerMessage:  localObsever onSetSuccess ");

            }

            @Override
            public void onCreateFailure(String s) {
                Log.d(ERROR, "handlePeerMessage:  localObsever onCreateFailure " + s);
            }


            @Override
            public void onSetFailure(String s) {

            }
        };

        // observer for remote callback functions
        SdpObserver remoteObserver = new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(LOG, "handlePeerMessage: remoteObserver onCreateSuccess:" + sessionDescription.description);
                pc.setLocalDescription(localObsever, sessionDescription);
                //create json object with parameters
                HashMap<String, String> params = new HashMap<>();
                params.put("type", "offer");
                params.put("sdp", sessionDescription.description);
                sendToPeer(peer_id, params);
            }

            @Override
            public void onSetSuccess() {

            }

            @Override
            public void onCreateFailure(String s) {
                Log.d(ERROR, "Create answer error: " + s);
            }

            @Override
            public void onSetFailure(String s) {

            }
        };

        if (data.getString("type").equals("offer")) {
            Log.d(LOG, "handlePeerMessage: Got offer " + data);
            createPeerConnection(peer_id);

            MediaConstraints mediaConstraints = new MediaConstraints();
            mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
            mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

            pc.setRemoteDescription(localObsever, new SessionDescription(SessionDescription.Type.OFFER, data.getString("sdp")));
            pc.createAnswer(remoteObserver, mediaConstraints);
        }
        else if (data.getString("type").equals("answer")) {
            Log.d(LOG, "handlePeerMessage: Got answer " + data);
            pc.setRemoteDescription(localObsever, new SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp")));
        }
        else {
            Log.d(LOG, "handlePeerMessage: Adding ICE candiate " + data);
            IceCandidate candidate = new IceCandidate(data.getString("sdpMid"), data.getInt("sdpMLineIndex"), data.getString("candidate"));
            pc.addIceCandidate(candidate);
        }
        Log.d(LOG, "handlePeerMessage: END");
    }

    /**
     * Updates the peer list adapter with any new entries
     */
    private void updatePeerList() {
        Log.d(LOG, "updatePeerList: ");
        try {
            for (Map.Entry<Integer, String> peer : otherPeers.entrySet()) {
                peers.add(peer.getValue());
                adapter.notifyDataSetChanged();
            }

        } catch (Throwable error) {
            Log.d(ERROR, error.toString());
        }
    }


    /**
     * Handles adding new servers to the list of servers
     * @param server: e.g. "renderingserver_phcherne@PHCHERNE-PR04,941,1"
     */
    private void handleServerNotification(String server) {
        Log.d(LOG, "handleServerNotification: " + server);
        String[] parsed = server.trim().split("\\s*,\\s*");;
        if (parseInt(parsed[2]) != 0) {
            otherPeers.put(parseInt(parsed[1]), parsed[0]);
        }
        updatePeerList();
    }

    /**
     * Handles adding new servers to list of severs and handles peer messages with the server (offer, answer, adding ICE candidate).
     * Loops on request timeout.
     */
    private void startHangingGet() {
        Log.d(LOG, "Start Hanging Get Start");
        // Created a custom request class to access headers of responses.
        CustomStringRequest stringRequest = new CustomStringRequest(Request.Method.GET, server + "/wait?peer_id=" + myID,
                new Response.Listener<CustomStringRequest.ResponseM>() {
                    @Override
                    public void onResponse(CustomStringRequest.ResponseM result) {
                        //From here you will get headers
                        Log.d(LOG, "startHangingGet: onResponse");
                        String peer_id_string = result.headers.get("Pragma");
                        int peer_id = parseInt(peer_id_string);
                        JSONObject response = result.response;

                        Log.d(LOG, "startHangingGet: Message from:" + peer_id + ':' + response);
                        if (peer_id == myID) {
                            // Update the list of peers
                            Log.d(LOG, "startHangingGet: peer if = myif");
                            try {
                                handleServerNotification(response.getString("Server"));
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        } else {
                            // Handle other messages from server
                            try {
                                Log.d(LOG, "startHangingGet: handlePeerMessage");
                                handlePeerMessage(peer_id, response);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d(ERROR, "startHangingGet: ERROR" + error.toString());
                        if (error.toString().equals("com.android.volley.TimeoutError")){
                            startHangingGet();
                        } else {
                            builder.setTitle(ERROR).setMessage("Sorry request did not work!");
                        }
                    }
                });
        getVolleyRequestQueue().add(stringRequest);

        Log.d(LOG, "Start Hanging Get END");
    }

    /**
     * Sends heartBeat request to server at a regular interval to maintain peerlist
     */
    private void startHeartBeat() {
        Log.d(LOG, "startHeartBeat: ");
        new Timer().scheduleAtFixedRate(new TimerTask(){
            @Override
            public void run(){
                try {
                    addRequest(server + "/heartbeat?peer_id=" + myID, Request.Method.GET, new Response.Listener<String>(){
                        @Override
                        public void onResponse(String response) {
                            // we don't really care what the response looks like here, so we don't observe it
                        }
                    });
                } catch (Throwable error) {
                    Log.d(ERROR, error.toString());
                }
            }
        },0, heartbeatIntervalInMs);
    }

    /**
     * Adds a http request to volley requestQueue.
     * @param url (String): http url
     * @param method (int): etc Request.Method.GET or Request.Method.POST
     * @param listener (Response.Listener<String>): custom listener for responses
     */
    private void addRequest(String url, int method, Response.Listener<String> listener){
        // Request a string response from the server
        StringRequest getRequest = new StringRequest(method, url, listener,
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        builder.setTitle(ERROR).setMessage("Sorry request did not work!");
                    }
                }){
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String>  params = new HashMap<>();
                params.put("Peer-Type", "Client");
                return params;
            }
        };
        // Add the request to the RequestQueue.
        getVolleyRequestQueue().add(getRequest);
    }

    public RequestQueue getVolleyRequestQueue() {
        if (requestQueue == null) {
            ArrayList<Interceptor> interceptors = new ArrayList<>();
            interceptors.add(new StethoInterceptor());
            requestQueue = Volley.newRequestQueue
                    (this, new OkHttpStack(interceptors));
        }

        return requestQueue;
    }

    private static String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String codecRtpMap = null;
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        String mediaDescription = "m=video ";
        if (isAudio) {
            mediaDescription = "m=audio ";
        }
        for (int i = 0; (i < lines.length) && (mLineIndex == -1 || codecRtpMap == null); i++) {
            if (lines[i].startsWith(mediaDescription)) {
                mLineIndex = i;
                continue;
            }
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
            }
        }
        if (mLineIndex == -1) {
            Log.w(LOG, "No " + mediaDescription + " line, so can't prefer " + codec);
            return sdpDescription;
        }
        if (codecRtpMap == null) {
            Log.w(LOG, "No rtpmap for " + codec);
            return sdpDescription;
        }
        Log.d(LOG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex]);
        String[] origMLineParts = lines[mLineIndex].split(" ");
        if (origMLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder();
            int origPartIndex = 0;
            // Format is: m=<media> <port> <proto> <fmt> ...
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(codecRtpMap);
            for (; origPartIndex < origMLineParts.length; origPartIndex++) {
                if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
                    newMLine.append(" ").append(origMLineParts[origPartIndex]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
            Log.d(LOG, "Change media description: " + lines[mLineIndex]);
        } else {
            Log.e(LOG, "Wrong SDP media description format: " + lines[mLineIndex]);
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }
}