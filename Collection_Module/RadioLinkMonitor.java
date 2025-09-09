/**
 * Copyright (C) 2024, Underground Cellular Project. All rights reserved.
 */

package com.android.internal.telephony;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * System-internal radio/link monitor class providing per-second radio/link state to registered callbacks.
 * Uses a dedicated handler thread for precise timing and to avoid blocking the main thread.
 */
public class RadioLinkMonitor {
    private static final String TAG = "RadioLinkMonitor";
    private static final long MONITORING_INTERVAL_MS = 1000; 
    private static final long MAX_CONSECUTIVE_FAILURES = 5; 
    private final TelephonyManager mTelephonyManager;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final RemoteCallbackList<IRadioLinkMetricsCallback> mCallbacks = new RemoteCallbackList<>();
    private final AtomicBoolean mIsMonitoring = new AtomicBoolean(false);
    private final AtomicLong mLastUpdateTime = new AtomicLong(0);
    private final AtomicLong mConsecutiveFailures = new AtomicLong(0);
    
    private volatile String mLatestJson = "{}";

    public interface IRadioLinkMetricsCallback {
        void onRadioLinkMetricsUpdated(String jsonMetrics);
    }

    public RadioLinkMonitor(Context context) {
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        
        mHandlerThread = new HandlerThread("RadioLinkMonitor");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        
        startMonitoring();
    }

    private void startMonitoring() {
        if (mIsMonitoring.compareAndSet(false, true)) {
            Log.i(TAG, "Starting radio link monitoring with " + MONITORING_INTERVAL_MS + "ms interval");
            
            mHandler.post(this::monitoringTask);
        }
    }

    private void monitoringTask() {
        if (!mIsMonitoring.get()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            String metricsJson = fetchMetrics();
            if (metricsJson != null) {
                mLatestJson = metricsJson;
                mLastUpdateTime.set(System.currentTimeMillis());
                mConsecutiveFailures.set(0); 

                broadcastMetrics(metricsJson);
            } else {
                Log.w(TAG, "Failed to fetch metrics (no data available)");
                long failures = mConsecutiveFailures.incrementAndGet();
                
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.e(TAG, "Too many consecutive failures (" + failures + "), considering stopping monitoring");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in monitoring task", e);
            long failures = mConsecutiveFailures.incrementAndGet();
            
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "Too many consecutive failures (" + failures + "), stopping monitoring");
                stopMonitoring();
                return;
            }
        }
        
        long executionTime = System.currentTimeMillis() - startTime;
        long nextDelay = Math.max(0, MONITORING_INTERVAL_MS - executionTime);
        
        if (mIsMonitoring.get()) {
            mHandler.postDelayed(this::monitoringTask, nextDelay);
        }
    }

    private String fetchMetrics() {
        try {
            // We extended TelephonyManager by integrating parsing interfaces provided by modem chipset manufacturers to enable the extraction of low-level modem logs.
            List<CellInfo> cellInfoList = mTelephonyManager.getAllCellInfo();
            if (cellInfoList != null && !cellInfoList.isEmpty()) {
                for (CellInfo info : cellInfoList) {
                    if (info instanceof CellInfoLte && info.isRegistered()) {
                        CellInfoLte lteInfo = (CellInfoLte) info;
                        CellSignalStrengthLte strength = lteInfo.getCellSignalStrength();
                        CellIdentityLte identity = (CellIdentityLte) lteInfo.getCellIdentity();

                        int rsrp = strength.getRsrp();
                        int rsrq = strength.getRsrq();
                        int snr = strength.getRssnr();
                        int tac = identity.getTac();
                        int ci = identity.getCi();
                        int pci = identity.getPci();
                        int earfcn = identity.getEarfcn();

                        String rat = getRatString(mTelephonyManager.getDataNetworkType());

                        int mimo = -1;
                        int mcs = -1;
                        try {
                            Object result = mTelephonyManager.getClass()
                                    .getMethod("getMimoLayers")
                                    .invoke(mTelephonyManager);
                            if (result instanceof Integer) {
                                mimo = (Integer) result;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "getMimoLayers() not available", e);
                        }

                        try {
                            Object result = mTelephonyManager.getClass()
                                    .getMethod("getMcsIndex")
                                    .invoke(mTelephonyManager);
                            if (result instanceof Integer) {
                                mcs = (Integer) result;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "getMcsIndex() not available", e);
                        }

                        return String.format(
                                "{\"rsrp\":%d,\"rsrq\":%d,\"snr\":%d,\"mcs\":%d,\"mimo\":%d,\"rat\":\"%s\",\"bs\":\"pci:%d,ci:%d,earfcn:%d\",\"timestamp\":%d}",
                                rsrp, rsrq, snr, mcs, mimo, rat, pci, ci, earfcn, System.currentTimeMillis());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch radio metrics", e);
        }
        return null;
    }

    private void broadcastMetrics(String jsonMetrics) {
        int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onRadioLinkMetricsUpdated(jsonMetrics);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to broadcast metrics to callback", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }

    public void registerCallback(IRadioLinkMetricsCallback callback) {
        if (callback != null) {
            mCallbacks.register(callback);
            Log.d(TAG, "Callback registered, total callbacks: " + mCallbacks.getRegisteredCallbackCount());
        }
    }

    public void unregisterCallback(IRadioLinkMetricsCallback callback) {
        if (callback != null) {
            mCallbacks.unregister(callback);
            Log.d(TAG, "Callback unregistered, total callbacks: " + mCallbacks.getRegisteredCallbackCount());
        }
    }

    public void stopMonitoring() {
        if (mIsMonitoring.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping radio link monitoring");
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    public void restartMonitoring() {
        stopMonitoring();
        startMonitoring();
    }

    private String getRatString(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            default:
                return "UNKNOWN";
        }
    }

    public String getLatestJson() {
        return mLatestJson;
    }
    public long getLastUpdateTime() {
        return mLastUpdateTime.get();
    }

    public void shutdown() {
        stopMonitoring();
        mHandlerThread.quitSafely();
        mCallbacks.kill();
    }
}