/**
 * Copyright (C) 2024, Underground Cellular Project. All rights reserved.
 */

package com.android.phone;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

import com.android.phone.DataRecordService;

public class SubwayDetector implements SensorEventListener {

    private static final String TAG = "SubwayDetector";

    private static final int STATE_OUT_STATION  = -1;
    private static final int STATE_IN_STATION  = 1;
    private static final int STATE_ON_TRAIN  = 2;

    private int mState = STATE_OUT_STATION;

    private final Context context;
    private final SensorManager sensorManager;
    private Sensor barometer, stepCounter, gyroscope, accelerometer;

    // The first stage
    private Queue<float> pressureQueue = new LinkedList<>();
    private static final long PRESSURE_MONITOR_DURATION_MS = 30000; // time window for pressure data
    private static final long STEP_MONITOR_DURATION_MS = 60000; // time window for step data
    private static final float PRESSURE_THRESHOLD = 0.05f; // an empirical threshold for pressure drop/increase
    private static final float PRESSURE_CHANGE_RATIO = 0.9f;
    private boolean pressureChangeDetected = false;
    private boolean continuousWalking = false;
    private Handler handler = new Handler();
    private Runnable pressureChangeRunnable;
    private Runnable pressureIncreaseRunnable;
    private long pressureChangeTimestamp = 0;

    // The seconde stage
    private Queue<float[]> gyroDataQueue = new LinkedList<>();
    private Queue<float[]> accelDataQueue = new LinkedList<>();
    private final int SAMPLE_WINDOW_SIZE = 100;
    private boolean trainDepartureDetected = false;


    public SubwayDetector(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        // get instances for sensors
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        startMonitoring();
        // check the pressure state every second
        pressureChangeRunnable = this::detectPressureChange;
        handler.postDelayed(pressureChangeRunnable, 1000);
    }

    // start monitoring sensors
    public void startMonitoring() {
        sensorManager.registerListener(this, barometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        Log.d(TAG, "Started monitoring sensors");
    }

    // stop monitoring sensors
    public void stopMonitoring() {
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(pressureCheckRunnable);
        Log.d(TAG, "Stopped monitoring sensors");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            recordPressureSample(event.values[0], System.currentTimeMillis());
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            recordStepEvent(System.currentTimeMillis());
        } else if (mState >= 0 && event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            storeGyroData(event.values);
        } else if (mState >= 0 && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            storeAccelData(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // The first stage
    private void recordPressureSample(float pressure, long timestamp) {
        pressureQueue.offer(new PressureData(pressure, timestamp));
        while (!pressureQueue.isEmpty() && (timestamp - pressureQueue.peek().timestamp > PRESSURE_MONITOR_DURATION_MS)) {
            pressureQueue.poll();
        }
    }

    private void recordStepEvent(long timestamp) {
        stepTimestamps.offer(timestamp);
        while (!stepTimestamps.isEmpty() && (timestamp - stepTimestamps.peek() > PRESSURE_MONITOR_DURATION_MS + STEP_MONITOR_DURATION_MS)) {
            stepTimestamps.poll();
        }
    }

    private void detectPressureChange(float currentPressure) {
        if (pressureQueue.size() > 1) {
            int totalPairs = 0;
            int exceedingThresholdCount = 0;

            Iterator<PressureData> iterator = pressureQueue.iterator();
            PressureData previousData = iterator.next();

            while (iterator.hasNext()) {
                PressureData currentData = iterator.next();
                float pressureDiff = 0;
                if (mState < 0){
                    pressureDiff = previousData.pressure - currentData.pressure;
                } else{
                    pressureDiff = currentData.pressure - previousData.pressure;
                }

                if (pressureDiff >= PRESSURE_DIFF_THRESHOLD) {
                    exceedingThresholdCount++;
                }

                totalPairs++;
                previousData = currentData;
            }

            // check whether there are enough pairs exceeding the threshold
            if (totalPairs > 0 && ((float) exceedingThresholdCount / totalPairs) >= PRESSURE_CHANGE_RATIO) {
                Log.d(TAG, "Sustained pressure change detected: " + exceedingThresholdCount + " of " + totalPairs + " pairs exceeded threshold");
                pressureChangeDetected = true;
                pressureChangeTimestamp = System.currentTimeMillis(); 

                // once detected a successive drop/increase, stop monitoring
                handler.removeCallbacks(detectPressureChange);
                detectBeforeContinuousWalking();
            }
        }

        if (!pressureChangeDetected) {
            handler.postDelayed(detectPressureChange, 1000);
        }
    }

    private void detectBeforeContinuousWalking() {
        int stepCountBefore = countStepsInWindow(pressureChangeTimestamp - STEP_MONITOR_DURATION_MS - PRESSURE_MONITOR_DURATION_MS, pressureChangeTimestamp - PRESSURE_MONITOR_DURATION_MS);

        Log.d(TAG, "Before step check: Steps before pressure change: " + stepCountBefore);

        if (stepCountBefore >= 50) {
            handler.postDelayed(this::detectAfterContinuousWalking, STEP_MONITOR_DURATION_MS);
        } else{
            pressureChangeDetected = false;
            handler.postDelayed(detectPressureChange, 1000);
        }
    }

    private void detectAfterContinuousWalking() {
        int stepCountAfter = countStepsInWindow(pressureChangeTimestamp, System.currentTimeMillis());

        Log.d(TAG, "After step check: Steps after pressure change: " + stepCountAfter);

        if (stepCountAfter >= 50) {
            if (mState < 0){
                Log.d(TAG, "Continuous walking detected before and after pressure change");
                mState = STATE_IN_STATION;
            }
            else if (mState == 2){
                Log.d(TAG, "Continuous walking detected before and after pressure increase");
                mState = STATE_OUT_STATION;
                stopRecordingService();
                pressureChangeDetected = false;
                continuousWalking = false;
                trainDepartureDetected = false;
                handler.postDelayed(pressureChangeRunnable, 1000);
            }
        }
    }

    private int countStepsInWindow(long startTime, long endTime) {
        int stepCount = 0;
        for (long timestamp : stepTimestamps) {
            if (timestamp >= startTime && timestamp <= endTime) {
                stepCount++;
            }
        }
        return stepCount;
    }

    // the second stage 
    private void storeGyroData(float[] values) {
        gyroDataQueue.offer(values.clone());
        if (gyroDataQueue.size() > SAMPLE_WINDOW_SIZE) {
            gyroDataQueue.poll();
        }
    }

    private void storeAccelData(float[] values) {
        accelDataQueue.offer(values.clone());
        if (accelDataQueue.size() > SAMPLE_WINDOW_SIZE) {
            accelDataQueue.poll();
        }
        checkTrainState();
    }

    private void checkTrainState() {
        if (gyroDataQueue.size() == SAMPLE_WINDOW_SIZE && accelDataQueue.size() == SAMPLE_WINDOW_SIZE) {
            float[][] gyroSamples = gyroDataQueue.toArray(new float[0][0]);
            float[][] accelSamples = accelDataQueue.toArray(new float[0][0]);

            if (runMLPClassifier(gyroSamples, accelSamples) == 1){
                if (mState!= STATE_ON_TRAIN) {
                    Log.d(TAG, "Train departure detected, device boards the train");
                    mState = STATE_ON_TRAIN;
                    startRecordingService();
                }
                else{
                    Log.d(TAG, "Train departure detected, device still on the train");
                    handler.removeCallbacks(pressureChangeRunnable);
                }
            }   else if (runMLPClassifier(gyroSamples, accelSamples) == 2 && mState== STATE_ON_TRAIN){
                Log.d(TAG, "Train stop detected");
                handler.postDelayed(pressureChangeRunnable, 1000);
            }
        }
    }

    private boolean runMLPClassifier(float[][] gyroSamples, float[][] accelSamples) {
        int result = 0;
        // we leverage a MLP-based binary classifier (which we will not disclose yet) for detecting train departures/stops
        // result =  MLPClassifier(gyroSamples, accelSamples); 
        return result; // 0 --> normal operation; 1 --> train departures; 2 --> train stops
    }

    private void startRecordingService() {
        // start data collection
        Log.d(TAG, "Starting recording service");
        Intent intent = new Intent(context, SubwayDataCollection.class);
        context.startService(intent);
    }

    private void stopRecordingService() {
        // stop data collection
        Log.d(TAG, "Stopping recording service");
        Intent intent = new Intent(context, SubwayDataCollection.class);
        context.stopService(intent);
    }

    private static class PressureData {
        float pressure;
        long timestamp;

        PressureData(float pressure, long timestamp) {
            this.pressure = pressure;
            this.timestamp = timestamp;
        }
    }
}