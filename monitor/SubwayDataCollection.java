/**
 * Copyright (C) 2024, Underground Cellular Project. All rights reserved.
 */

package com.android.phone;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TrafficStats;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import ai.onnxruntime.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;


public class SubwayDataCollection extends Service {
    private static final String TAG = "SubwayDataCollection";

    private TelephonyManager telephonyManager;
    private ActivityManager activityManager;
    private String foregroundApp;
    private int foregroundUID;
    private int foregroundPID;
    private long totalTxBytes;
    private long totalRxBytes;
    private int phoneID;
    // update every second
    private int dnsCount;
    private int dnsSuccessCount;
    private long dnsLatencyCount;
    // update in real time
    private int dnsCountNow;
    private int dnsSuccessCountNow;
    private long dnsLatencyCountNow;
    private int vssLabel;


    // binder for VSS detection
    private final IBinder binder = new FrameStatsBinder();

    // VSS prediction
    private static final int HISTORICAL_SIZE = 30;  // historical window size
    private static final int FEATURE_DIM = 11;  // feature size
    private final Deque<float[]> rollingBuffer = new ArrayDeque<>(HISTORICAL_SIZE);
    
    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    
    // only collect data when the target app is running foreground
    private static final Set<String> TARGET_APPS = new HashSet<>(Arrays.asList(
    "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.smile.gifmaker", 
    "com.tencent.mm", "com.tencent.qqlive", "com.kuaishou.nebula", "tv.danmaku.bili", 
    "com.youku.phone", "com.duowan.kiwi", "com.tencent.wemeet.app"
    ));

    private boolean isCollectingData = false;
    private final Handler handler = new Handler();    
    private final long CHECK_INTERVAL_MS = 1000;
    private final long COLLECTION_INTERVAL_MS = 1000;
    private final List<List<Object>> collectedData = new ArrayList<>();
    private final int VSS_THRESHOLD = 20;


    @Override
    public void onCreate() {
        super.onCreate();
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        startListeningForDnsEvents();
        ServiceManager.addService("com.android.phone.SubwayDataCollection", binder);
        Log.d(TAG, "SubwayDataCollection service created");
        handler.post(checkForegroundAppRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "SubwayDataCollection service started");
        return START_STICKY;
    }

    private final Runnable checkForegroundAppRunnable = new Runnable() {
        @Override
        public void run() {
            if (isForegroundAppInTargetList()) {
                if (!isCollectingData) {
                    ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
                    foregroundUID = appInfo.uid;
                    for (ActivityManager.RunningAppProcessInfo processInfo : activityManager.getRunningAppProcesses()) {
                        if (processInfo.processName.equals(packageName)) {
                            foregroundPID = processInfo.pid;
                        }
                    }
                    totalTxBytes = TrafficStats.getUidTxBytes(uid);
                    totalRxBytes = TrafficStats.getUidRxBytes(uid);
                    int subID = subscriptionManager.getDefaultDataSubscriptionId();
                    phoneID = SubscriptionManager.getPhoneId(subID);
                    dnsCount = 0;
                    dnsSuccessCount = 0;
                    dnsLatencyCount = 0;
                    dnsCountNow = 0;
                    dnsSuccessCountNow = 0;
                    dnsLatencyCountNow = 0;
                    Log.d(TAG, "Target app detected, starting data collection");
                    isCollectingData = true;
                    // only collect data when the target app is running foreground
                    handler.postDelayed(dataCollectionRunnable, COLLECTION_INTERVAL_MS);
                }
            } else {
                if (isCollectingData) {
                    Log.d(TAG, "No target app in foreground, stopping data collection");
                    isCollectingData = false;
                    handler.removeCallbacks(dataCollectionRunnable);
                }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private boolean isForegroundAppInTargetList() {
        List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            foregroundApp = tasks.get(0).topActivity.getPackageName();
            boolean isInList = TARGET_APPS.contains(foregroundApp);
            Log.d(TAG, "Foreground app: " + foregroundApp + ", is target: " + isInList);
            return isInList;
        }
        return false;
    }

    private final Runnable dataCollectionRunnable = new Runnable() {
    @Override
        public void run() {
            collectData();
            if (isCollectingData) {
                handler.postDelayed(this, COLLECTION_INTERVAL_MS);
            }
        }
    };


    private void collectData() {
        float[] tcpInfo = getTCPInfo(); // collect rtt and packet loss rate
        // update dns info only when there are new dns events
        float dnsSuccessRate = ((dnsCountNow - dnsCount) > 0) ? ((float) (dnsSuccessCountNow - dnsSuccessCount) / (dnsCountNow - dnsCount)) : -1;
        float dnsLatency = ((dnsCountNow - dnsCount) > 0) ? ((float) (dnsLatencyCountNow - dnsLatencyCount) / (dnsCountNow - dnsCount)) : -1;
        if ((dnsCountNow - dnsCount) > 0){
            dnsCount = dnsCountNow;
            dnsSuccessCount = dnsSuccessCountNow;
            dnsLatencyCount = dnsLatencyCountNow;
        }
        long[] bandwidthInfo = getBandwidthInfo(); // collect ul/dl bandwidth
        float[] radioInfo = getRadioInfo(phoneID); // get radio information by phoneID (including RAT, RSRP, SNR, CID, MIMO layer, MCS, etc.) with APIs provided by the modem chipset manufacturers

        List<Object> record = new ArrayList<>();
        for (float item: tcpInfo){
            record.add(item);}
        record.add(dnsSuccessRate);
        record.add(dnsLatency);
        for (long item: bandwidthInfo){
            record.add(item);}
        for (float item: radioInfo){
            record.add(item);}
        record.add(vssLabel);
        collectedData.add(record);

        // data for real-time prediction
        if (rollingBuffer.size() >= HISTORICAL_SIZE) {
            rollingBuffer.pollFirst();
        }
        rollingBuffer.addLast(record);
        if (rollingBuffer.size() == HISTORICAL_SIZE) {
            runOnnxInference();
        }
    }

    private float[] getTCPInfo(){
        String inode = getSocketInode(foregroundPID);
        if (inode == null) {
            Log.e(TAG, "No socket found for PID: " + foregroundPID);
            return new float[0];
        }
        return fetchTCPInfoFromNetlink(inode);
    }

    private void startListeningForDnsEvents() {
        // the NetdEventListener for DNS events
        NetdEventListenerService listener = new NetdEventListenerService() {
            @Override
            public void onDnsEvent(int netId, int eventType, int returnCode, int latencyMs, String hostname, String[] ipAddresses, int uid) {
                if (uid == foregroundUID) {
                    dnsCountNow++;
                    dnsTotalLatencyNow += latencyMs;
                    if (returnCode == 0) {  // success 
                        dnsSuccessCountNow++;
                    }
                }
            }
        };
        NetdEventListenerService.registerListener(listener);
    }

    private long[] getBandwidthInfo(){
        long currentTxBytes = TrafficStats.getUidTxBytes(uid);
        long currentRxBytes = TrafficStats.getUidRxBytes(uid);
        long[] tmp = {currentTxBytes-totalTxBytes, currentRxBytes-totalRxBytes};
        totalTxBytes = currentTxBytes;
        totalRxBytes = currentRxBytes;
        return tmp;
    }

    // process the frame rate from surfaceflinger
    public class FrameStatsBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == FIRST_CALL_TRANSACTION) {
                int frameCount = data.readInt();
                long timestamp = data.readLong();
                if (frameCount < VSS_THRESHOLD){
                    vssLabel = 1;
                } else{
                    vssLabel = 0;
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // get TCP info from the TcpInfo structure
    private float[] fetchTCPInfoFromNetlink(String inode) {
        try (DatagramSocket socket = new DatagramSocket()) {
            ByteBuffer request = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
            request.putShort((short) 36);  // nlmsg_len
            request.putShort((short) 20);  // nlmsg_type
            request.putInt(0);             // nlmsg_flags
            request.putInt(1);             // nlmsg_seq
            Os.write(socket.getFileDescriptor$(), request.array(), 0, request.position());
            ByteBuffer response = ByteBuffer.allocate(1024);
            int bytesRead = Os.read(socket.getFileDescriptor$(), response.array(), 0, 1024);

            if (bytesRead > 0) {
                response.order(ByteOrder.nativeOrder());
                float rtt = parseRTT(response);  // parse RTT from response
                float lossRate = parseLossRate(response); // parse loss rate from response
                return float[]{rtt, lossRate};
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch RTT from Netlink", e);
        }
        return new float[0];
    }


    // get Socket inode from /proc/[PID]/net/tcp
    private String getSocketInode(int pid) {
        String path = "/proc/" + pid + "/net/tcp";
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 9) {
                    return fields[9];
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    // real-time prediction with pre-trained model
    private void runOnnxInference() {
        if (ortSession == null) {
            Log.e(TAG, "ONNX Session is not initialized");
            return;
        }

        try {
            float[] inputArray = new float[HISTORICAL_SIZE * FEATURE_DIM];
            int i = 0;
            for (float[] row : rollingBuffer) {
                for (float val : row) {
                    inputArray[i++] = val;
                }
            }

            // create the OnnxTensor, (1, 30, 11)
            FloatBuffer inputBuffer = FloatBuffer.wrap(inputArray);
            long[] shape = new long[]{1, HISTORICAL_SIZE, FEATURE_DIM};
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, shape);

            // run the model
            OrtSession.Result result = ortSession.run(Collections.singletonMap("input", inputTensor));
            float[] output = ((float[][]) result.get(0).getValue())[0];

            Log.d(TAG, "ONNX Model Prediction Output: " + Arrays.toString(output));

        } catch (Exception e) {
            Log.e(TAG, "ONNX Inference Failed", e);
        }
    }
}
