/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.power;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.util.Slog;

import com.android.systemui.SystemUI;

public class PowerUI extends SystemUI {
    static final String TAG = "PowerUI";

    static final boolean DEBUG = false;

    Handler mHandler = new Handler();

    int mBatteryLevel = 100;
    int mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
    int mPlugType = 0;
    int mInvalidCharger = 0;

    int mLowBatteryAlertCloseLevel;
    int[] mLowBatteryReminderLevels = new int[2];
    
    boolean mIntentSendBatteryLow = false;
    boolean mIntentSendInvalidCharger = false;

    // For filtering ACTION_POWER_DISCONNECTED on boot
    boolean mIgnoreFirstPowerEvent = true;

    public void start() {

        mLowBatteryAlertCloseLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningLevel);
        mLowBatteryReminderLevels[0] = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryReminderLevels[1] = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);

        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
    }

    /**
     * Buckets the battery level.
     *
     * The code in this function is a little weird because I couldn't comprehend
     * the bucket going up when the battery level was going down. --joeo
     *
     * 1 means that the battery is "ok"
     * 0 means that the battery is between "ok" and what we should warn about.
     * less than 0 means that the battery is low
     */
    private int findBatteryLevelBucket(int level) {
        if (level >= mLowBatteryAlertCloseLevel) {
            return 1;
        }
        if (level >= mLowBatteryReminderLevels[0]) {
            return 0;
        }
        final int N = mLowBatteryReminderLevels.length;
        for (int i=N-1; i>=0; i--) {
            if (level <= mLowBatteryReminderLevels[i]) {
                return -1-i;
            }
        }
        throw new RuntimeException("not possible!");
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                final int oldBatteryLevel = mBatteryLevel;
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                final int oldBatteryStatus = mBatteryStatus;
                mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                final int oldPlugType = mPlugType;
                mPlugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1);
                final int oldInvalidCharger = mInvalidCharger;
                mInvalidCharger = intent.getIntExtra(BatteryManager.EXTRA_INVALID_CHARGER, 0);

                final boolean plugged = mPlugType != 0;
                final boolean oldPlugged = oldPlugType != 0;

                if (mIgnoreFirstPowerEvent && plugged) {
                    mIgnoreFirstPowerEvent = false;
                }

                int oldBucket = findBatteryLevelBucket(oldBatteryLevel);
                int bucket = findBatteryLevelBucket(mBatteryLevel);

                if (DEBUG) {
                    Slog.d(TAG, "buckets   ....." + mLowBatteryAlertCloseLevel
                            + " .. " + mLowBatteryReminderLevels[0]
                            + " .. " + mLowBatteryReminderLevels[1]);
                    Slog.d(TAG, "level          " + oldBatteryLevel + " --> " + mBatteryLevel);
                    Slog.d(TAG, "status         " + oldBatteryStatus + " --> " + mBatteryStatus);
                    Slog.d(TAG, "plugType       " + oldPlugType + " --> " + mPlugType);
                    Slog.d(TAG, "invalidCharger " + oldInvalidCharger + " --> " + mInvalidCharger);
                    Slog.d(TAG, "bucket         " + oldBucket + " --> " + bucket);
                    Slog.d(TAG, "plugged        " + oldPlugged + " --> " + plugged);
                }

                if (oldInvalidCharger == 0 && mInvalidCharger != 0) {
                    Slog.d(TAG, "showing invalid charger warning");
                    showInvalidChargerDialog();
                    return;
                } else if (oldInvalidCharger != 0 && mInvalidCharger == 0) {
                    dismissInvalidChargerDialog();
                } else if (mIntentSendInvalidCharger) {
                    // if invalid charger is showing, don't show low battery
                    return;
                }

                if (!plugged
                        && (bucket < oldBucket || oldPlugged)
                        && mBatteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN
                        && bucket < 0) {
                    showLowBatteryWarning();
                } else if (plugged || (bucket > oldBucket && bucket > 0)) {
                    dismissLowBatteryWarning();
                } else if (mIntentSendBatteryLow) {
                    showLowBatteryWarning();
                }
            } else if (action.equals(Intent.ACTION_POWER_CONNECTED)
                    || action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                if (mIgnoreFirstPowerEvent) {
                    mIgnoreFirstPowerEvent = false;
                }
            } else {
                Slog.w(TAG, "unknown intent: " + intent);
            }
        }
    };

    void dismissLowBatteryWarning() {       
        mContext.sendBroadcast(new Intent(Intent.CRAVEOS_ACTION_BATTERY_OK));
        mIntentSendBatteryLow = false;
    }

    void showLowBatteryWarning() {
        Slog.i(TAG,
                ((!mIntentSendBatteryLow) ? "showing" : "updating")
                + " low battery warning: level=" + mBatteryLevel
                + " [" + findBatteryLevelBucket(mBatteryLevel) + "]");

        // ----------
        // CraveOS - Don't show low battery dialog, instead send an intent which can be handled by our apps
        Intent intent = new Intent(Intent.CRAVEOS_ACTION_BATTERY_LOW);
        intent.putExtra(Intent.CRAVEOS_EXTRA_BATTERY_LEVEL, mBatteryLevel);
        mContext.sendBroadcast(intent);
        
        mIntentSendBatteryLow = true;
    }

    void dismissInvalidChargerDialog() {
        mContext.sendBroadcast(new Intent(Intent.CRAVEOS_ACTION_BATTERY_OK));
        mIntentSendInvalidCharger = false;
    }

    void showInvalidChargerDialog() {
        Slog.d(TAG, "showing invalid charger dialog");

        // ----------
        // CraveOS - Send intent when an invalid charger is used
        Intent intent = new Intent(Intent.CRAVEOS_ACTION_INVALID_CHARGER);
        intent.putExtra(Intent.CRAVEOS_EXTRA_BATTERY_LEVEL, mBatteryLevel);
        mContext.sendBroadcast(intent);
        
        mIntentSendInvalidCharger = true;
    }
    
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("mLowBatteryAlertCloseLevel=");
        pw.println(mLowBatteryAlertCloseLevel);
        pw.print("mLowBatteryReminderLevels=");
        pw.println(Arrays.toString(mLowBatteryReminderLevels));
        pw.print("mIntentSendInvalidCharger=");
        pw.println(mIntentSendInvalidCharger);
        pw.print("mIntentSendBatteryLow=");
        pw.println(mIntentSendBatteryLow);
        pw.print("mBatteryLevel=");
        pw.println(Integer.toString(mBatteryLevel));
        pw.print("mBatteryStatus=");
        pw.println(Integer.toString(mBatteryStatus));
        pw.print("mPlugType=");
        pw.println(Integer.toString(mPlugType));
        pw.print("mInvalidCharger=");
        pw.println(Integer.toString(mInvalidCharger));
        pw.print("bucket: ");
        pw.println(Integer.toString(findBatteryLevelBucket(mBatteryLevel)));
    }
}

