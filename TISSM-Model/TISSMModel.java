package com.android.phone;

import android.content.Context;
import android.telephony.SignalStrength;
import android.util.Log;
import android.os.Handler;
import android.os.HandlerThread;
import com.android.internal.telephony.TelephonyStatAdapter;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Random;
import java.util.Arrays;

public class TISSMModel implements TelephonyStatAdapter.ICallBack {
    private static final String TAG = "TISSMModel";
    private static final int SIGNAL_THRESHOLD = -100;
    private static final long ESTIMATION_INTERVAL_MS = 1.2;
    private static final double DELTA_T = 0.2; // adjust according to the measurement period

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TelephonyStatAdapter mTelephonyStatAdapter;

    private final Deque<Double> rsrpHistory = new LinkedList<>();
    private final Random random = new Random();

    private double rsrpEstimate = -90;
    private double betaEstimate = 0;
    private double alphaEstimate = 0;

    private double noise_r = 0.1;
    private double noise_b = 0.05;
    private double noise_a = 0.02;
    private double sum_squared_error_R = 0;
    private double sum_squared_error_B = 0;
    private double sum_squared_error_A = 0;
    private int num_updates = 0;

    private double[][] P_t = {
            {noise_r, 0, 0},
            {0, noise_b, 0},
            {0, 0, noise_a}
    };

    // state transition matix
    private double[][] M = {
            {1, DELTA_T, 0.5 * DELTA_T * DELTA_T},
            {0, 1, DELTA_T},
            {0, 0, 1}
    };

    public TISSMModel(Context context) {
        mHandlerThread = new HandlerThread("TISSMModelThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mTelephonyStatAdapter = new TelephonyStatAdapter();
        mTelephonyStatAdapter.registerCallback(this);
    }

    @Override
    public void onSignalStrengthChanged(int phoneId, SignalStrength signalStrength) {
        double rsrp = getRsrpFromSignalStrength(signalStrength);

        if (rsrpHistory.size() > 20) {
            rsrpHistory.removeFirst(); // only record the most recent rsrp values
        }
        rsrpHistory.addLast(rsrp);

        double estimatedRsrp = tissmEstimate();

        if (estimatedRsrp < SIGNAL_THRESHOLD) {
            notifyModemTriggerHO(phoneId);
        }
    }

    private double getRsrpFromSignalStrength(SignalStrength signalStrength) {
        return signalStrength.getCellSignalStrengths().get(0).getDbm();
    }

    private double tissmEstimate() {
        if (rsrpHistory.size() < 10) return rsrpEstimate; // return a normal value

        Double[] RSRP = rsrpHistory.toArray(new Double[0]);
        int n = RSRP.length;

        for (int t = 0; t < n - 1; t++) {
            // estimation stage
            double[] noise = {
                    random.nextGaussian() * Math.sqrt(noise_r),
                    random.nextGaussian() * Math.sqrt(noise_b),
                    random.nextGaussian() * Math.sqrt(noise_a)
            };
            rsrpEstimate = rsrpEstimate + betaEstimate * DELTA_T + 0.5 * alphaEstimate * DELTA_T * DELTA_T + noise[0];
            betaEstimate = betaEstimate + alphaEstimate * DELTA_T + noise[1];
            alphaEstimate = alphaEstimate + noise[2];

            // correction stage
            double[][] W_t = {
                    {noise_r, 0, 0},
                    {0, noise_b, 0},
                    {0, 0, noise_a}
            };
            P_t = matrixAdd(matrixMultiply(M, P_t, M), W_t);

            // rsrp residual error
            double error_R = RSRP[t + 1] - rsrpEstimate;
            double error_B = (RSRP[t + 1] - RSRP[t]) - betaEstimate;
            double error_A = (betaEstimate - (RSRP[t] - RSRP[t - 1])) - alphaEstimate;

            // kalman gain
            double[] F = {1, 0, 0};
            double[][] Ft = transpose(F);
            double S = matrixMultiply(F, P_t, Ft)[0][0] + noise_r;
            double[][] K_t = matrixMultiply(P_t, Ft, 1.0 / S);

            // correct the state
            double[] correction = matrixMultiply(K_t, new double[]{error_R});
            rsrpEstimate += correction[0];
            betaEstimate += correction[1];
            alphaEstimate += correction[2];

            // update matix P
            double[][] I_KF = matrixSubtract(identityMatrix(3), matrixMultiply(K_t, new double[][]{F}));
            P_t = matrixMultiply(I_KF, P_t);

            // update noises
            sum_squared_error_R += error_R * error_R;
            sum_squared_error_B += error_B * error_B;
            sum_squared_error_A += error_A * error_A;
            num_updates++;

            // update the noise variances with MLE
            if (num_updates % 10 == 0) {
                noise_r = sum_squared_error_R / num_updates;
                noise_b = sum_squared_error_B / num_updates;
                noise_a = sum_squared_error_A / num_updates;

                sum_squared_error_R = 0;
                sum_squared_error_B = 0;
                sum_squared_error_A = 0;
                num_updates = 0;
            }
        }
        // estimate RSRP for HO trigger
        rsrpEstimateTmp = rsrpEstimate;
        for (int t = 0; t < ESTIMATION_INTERVAL_MS/DELTA_T; t++){
            double[] noise = {
                    random.nextGaussian() * Math.sqrt(noise_r),
                    random.nextGaussian() * Math.sqrt(noise_b),
                    random.nextGaussian() * Math.sqrt(noise_a)
            };
            rsrpEstimateTmp = rsrpEstimateTmp + betaEstimate * DELTA_T + 0.5 * alphaEstimate * DELTA_T * DELTA_T + Arrays.stream(noise).sum();
        }
        return rsrpEstimateTmp;
    }

    private void notifyModemTriggerHO(int phoneId) {
        sendModemCommand(phoneId, "AT+SEND_A2"); // trigger the handover by sending a calibrated measurement report to BS
    }

}
