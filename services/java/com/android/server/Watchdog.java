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

package com.android.server;

import com.android.server.am.ActivityManagerService;
import com.android.server.power.PowerManagerService;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/** This class calls its monitor every minute. Killing this process if they don't return **/
public class Watchdog extends Thread {
    static final String TAG = "Watchdog";
    static final boolean localLOGV = false || false;

    // Set this to true to use debug default values.
    static final boolean DB = false;

    // Set this to true to have the watchdog record kernel thread stacks when it fires
    static final boolean RECORD_KERNEL_THREADS = true;

    static final int MONITOR = 2718;

    static final int TIME_TO_RESTART = DB ? 15*1000 : 60*1000;
    static final int TIME_TO_WAIT = TIME_TO_RESTART / 2;

    static final int MEMCHECK_DEFAULT_MIN_SCREEN_OFF = DB ? 1*60 : 5*60;   // 5 minutes
    static final int MEMCHECK_DEFAULT_MIN_ALARM = DB ? 1*60 : 3*60;        // 3 minutes
    static final int MEMCHECK_DEFAULT_RECHECK_INTERVAL = DB ? 1*60 : 5*60; // 5 minutes

    static final int REBOOT_DEFAULT_INTERVAL = DB ? 1 : 0;                 // never force reboot
    static final int REBOOT_DEFAULT_START_TIME = 3*60*60;                  // 3:00am
    static final int REBOOT_DEFAULT_WINDOW = 60*60;                        // within 1 hour

    static final String REBOOT_ACTION = "com.android.service.Watchdog.REBOOT";

    static final String[] NATIVE_STACKS_OF_INTEREST = new String[] {
        "/system/bin/mediaserver",
        "/system/bin/sdcard",
        "/system/bin/surfaceflinger"
    };

    static Watchdog sWatchdog;

    /* This handler will be used to post message back onto the main thread */
    final Handler mHandler;
    final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
    Context mContext;
    ContentResolver mResolver;
    BatteryService mBattery;
    PowerManagerService mPower;
    AlarmManagerService mAlarm;
    ActivityManagerService mActivity;
    boolean mCompleted;
    boolean mForceKillSystem;
    Monitor mCurrentMonitor;

    int mPhonePid;

    final Calendar mCalendar = Calendar.getInstance();
    int mMinScreenOff = MEMCHECK_DEFAULT_MIN_SCREEN_OFF;
    int mMinAlarm = MEMCHECK_DEFAULT_MIN_ALARM;
    boolean mNeedScheduledCheck;
    PendingIntent mCheckupIntent;
    PendingIntent mRebootIntent;

    long mBootTime;
    int mRebootInterval;

    boolean mReqRebootNoWait;     // should wait for one interval before reboot?
    int mReqRebootInterval = -1;  // >= 0 if a reboot has been requested
    int mReqRebootStartTime = -1; // >= 0 if a specific start time has been requested
    int mReqRebootWindow = -1;    // >= 0 if a specific window has been requested
    int mReqMinScreenOff = -1;    // >= 0 if a specific screen off time has been requested
    int mReqMinNextAlarm = -1;    // >= 0 if specific time to next alarm has been requested
    int mReqRecheckInterval= -1;  // >= 0 if a specific recheck interval has been requested

    /**
     * Used for scheduling monitor callbacks and checking memory usage.
     */
    final class HeartbeatHandler extends Handler {
        HeartbeatHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MONITOR: {
                    // See if we should force a reboot.
                    int rebootInterval = mReqRebootInterval >= 0
                            ? mReqRebootInterval : REBOOT_DEFAULT_INTERVAL;
                    if (mRebootInterval != rebootInterval) {
                        mRebootInterval = rebootInterval;
                        // We have been running long enough that a reboot can
                        // be considered...
                        checkReboot(false);
                    }

                    final int size = mMonitors.size();
                    for (int i = 0 ; i < size ; i++) {
                        mCurrentMonitor = mMonitors.get(i);
                        mCurrentMonitor.monitor();
                    }

                    synchronized (Watchdog.this) {
                        mCompleted = true;
                        mCurrentMonitor = null;
                    }
                } break;
            }
        }
    }

    final class RebootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (localLOGV) Slog.v(TAG, "Alarm went off, checking reboot.");
            checkReboot(true);
        }
    }

    final class RebootRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            mReqRebootNoWait = intent.getIntExtra("nowait", 0) != 0;
            mReqRebootInterval = intent.getIntExtra("interval", -1);
            mReqRebootStartTime = intent.getIntExtra("startTime", -1);
            mReqRebootWindow = intent.getIntExtra("window", -1);
            mReqMinScreenOff = intent.getIntExtra("minScreenOff", -1);
            mReqMinNextAlarm = intent.getIntExtra("minNextAlarm", -1);
            mReqRecheckInterval = intent.getIntExtra("recheckInterval", -1);
            EventLog.writeEvent(EventLogTags.WATCHDOG_REQUESTED_REBOOT,
                    mReqRebootNoWait ? 1 : 0, mReqRebootInterval,
                            mReqRecheckInterval, mReqRebootStartTime,
                    mReqRebootWindow, mReqMinScreenOff, mReqMinNextAlarm);
            checkReboot(true);
        }
    }
    
    final class CraveIntentReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(Intent.CRAVEOS_ACTION_SET_BACKLIGHT)) {
				setBacklight(intent);
			} else if (action.equals(Intent.CRAVEOS_ACTION_TURN_SCREEN_ONOFF)) {
				turnScreenOnOff();
			} else if (action.equals(Intent.CRAVEOS_ACTION_REBOOT)) {
				PowerManagerService pms = (PowerManagerService)ServiceManager.getService("power");
				pms.reboot(false, "CraveOS reboot initiated", false);
			} else if (action.equals(Intent.CRAVEOS_ACTION_SHUTDOWN)) {
				PowerManagerService pms = (PowerManagerService)ServiceManager.getService("power");
				pms.shutdown(false, false);
			} else if (action.equals(Intent.CRAVEOS_ACTION_INSTALL_APK)) {
				if (!intent.hasExtra(Intent.CRAVEOS_EXTRA_PACKAGE_PATH)) {
					Slog.w(TAG, "Install APK: Missing PackagePath extra");
					return;
				}
				
				String fileStr = intent.getStringExtra(Intent.CRAVEOS_EXTRA_PACKAGE_PATH);
				Slog.v(TAG, "Install APK: " + fileStr);
				
				PackageManager pm = mContext.getPackageManager();
		        pm.installPackage(Uri.fromFile(new File(fileStr)), 
		        		new CraveInstallPackageObserver(), 
		        		PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_FROM_ADB, 
		        		null);
			} else if (action.equals(Intent.CRAVEOS_ACTION_REMOVE_APK)) {
				if (!intent.hasExtra(Intent.CRAVEOS_EXTRA_APK_PACKAGE_NAME)) {
					Slog.w(TAG, "Install APK: Missing packageName extra");
					return;
				}
				
				String fileStr = intent.getStringExtra(Intent.CRAVEOS_EXTRA_APK_PACKAGE_NAME);
				Slog.v(TAG, "Removing APK: " + fileStr);
				
				PackageManager pm = mContext.getPackageManager();
				pm.deletePackage(fileStr, new CraveDeletePackageObserver(), PackageManager.DELETE_ALL_USERS);
			} else if (action.equals(Intent.CRAVEOS_ACTION_SET_DATETIME)) {
				setDeviceDateTime(intent);
			} else if (action.equals(Intent.CRAVEOS_ACTION_ADB_ENABLE)) {
				toggleAdb(true);
			} else if (action.equals(Intent.CRAVEOS_ACTION_ADB_DISABLE)) {
				toggleAdb(false);
			} else if (action.equals(Intent.CRAVEOS_ACTION_ADB_WIFI_ENABLE)) {
				toggleAdbWifi(true);
			} else if (action.equals(Intent.CRAVEOS_ACTION_ADB_WIFI_DISABLE)) {
				toggleAdbWifi(false);
			} else if (action.equals(Intent.CRAVEOS_ACTION_SET_LOCALE)) {
				setLocale(intent);
			} else if (action.equals(Intent.CRAVEOS_ACTION_CLEAR_USERDATA)) {
				String packageName = intent.getStringExtra(Intent.CRAVEOS_EXTRA_CLEAR_PACKAGENAME);
				if (packageName == null)
					packageName = "";
				
				clearAppUserData(packageName);
			} else if (action.equals(Intent.CRAVEOS_ACTION_CLEAR_CACHE)) {
				String packageName = intent.getStringExtra(Intent.CRAVEOS_EXTRA_CLEAR_PACKAGENAME);
				if (packageName == null)
					packageName = "";
				
				clearAppCache(packageName);
			} else {
				Slog.w(TAG, "CraveOS - Received unknown intent: " + action);
			}
		}
    }
    
    final class CraveInstallPackageObserver implements IPackageInstallObserver {
		@Override
		public IBinder asBinder() {
			Slog.d(TAG, "CraveInstallerPackageObserver - asBinder called!");
			return null;
		}

		@Override
		public void packageInstalled(String packageName, int returnCode)
				throws RemoteException {
			Slog.i(TAG, "CraveInstallerPackageObserver - Install APK finished: packageName = " + packageName + ", returnCode = " + returnCode);
			
			Intent intent = new Intent(Intent.CRAVEOS_ACTION_INSTALL_APK_RESULT);
			intent.putExtra(Intent.CRAVEOS_EXTRA_APK_RETURN_CODE, returnCode);
			intent.putExtra(Intent.CRAVEOS_EXTRA_APK_PACKAGE_NAME, packageName);
			mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
		}
    }    
    
    final class CraveDeletePackageObserver implements IPackageDeleteObserver {
		@Override
		public IBinder asBinder() {
			Slog.d(TAG, "CraveDeletePackageObserver - asBinder called!");
			return null;
		}

		@Override
		public void packageDeleted(String packageName, int returnCode)
				throws RemoteException {
			Slog.i(TAG, "CraveDeletePackageObserver - Delete APK finished: packageName = " + packageName + ", returnCode = " + returnCode);
			
			Intent intent = new Intent(Intent.CRAVEOS_ACTION_DELETE_APK_RESULT);
			intent.putExtra(Intent.CRAVEOS_EXTRA_APK_RETURN_CODE, returnCode);
			intent.putExtra(Intent.CRAVEOS_EXTRA_APK_PACKAGE_NAME, packageName);
			mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
		}
    }
    
    final class CpuGovernorCheck implements Monitor {
    	public static final String GOV_FILE = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor";
    	static final String GOV_INTERACTIVE = "interactive";
    	
    	public void monitor() {
    		String currentScalingGovernor = "";
    		if (fileExists(GOV_FILE)) {
    			currentScalingGovernor = fileReadOneLine(GOV_FILE);
    		}
    		
    		if (!currentScalingGovernor.equals(GOV_INTERACTIVE)) {
    			Slog.i(TAG, "Changed cpu scaling governor from '" + currentScalingGovernor + "' to 'interactive'");
    			fileWriteOneLine(GOV_FILE, GOV_INTERACTIVE);
    		}
    	}
    	
    	public boolean fileExists(String filename) {
            return new File(filename).exists();
        }

        public String fileReadOneLine(String fname) {
            BufferedReader br;
            String line = null;

            try {
                br = new BufferedReader(new FileReader(fname), 512);
                try {
                    line = br.readLine();
                } finally {
                    br.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "IO Exception when reading /sys/ file", e);
            }
            return line;
        }

        public boolean fileWriteOneLine(String fname, String value) {
            try {
                FileWriter fw = new FileWriter(fname);
                try {
                    fw.write(value);
                } finally {
                    fw.close();
                }
            } catch (IOException e) {
                String Error = "Error writing to " + fname + ". Exception: ";
                Log.e(TAG, Error, e);
                return false;
            }
            return true;
        }
    }

    public interface Monitor {
        void monitor();
    }

    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }

        return sWatchdog;
    }

    private Watchdog() {
        super("watchdog");
        // Explicitly bind the HeartbeatHandler to run on the ServerThread, so
        // that it can't get accidentally bound to another thread.
        mHandler = new HeartbeatHandler(Looper.getMainLooper());
    }

    public void init(Context context, BatteryService battery,
            PowerManagerService power, AlarmManagerService alarm,
            ActivityManagerService activity) {
    	mContext = context;
        mResolver = context.getContentResolver();
        mBattery = battery;
        mPower = power;
        mAlarm = alarm;
        mActivity = activity;

        context.registerReceiver(new RebootReceiver(),
                new IntentFilter(REBOOT_ACTION));
        mRebootIntent = PendingIntent.getBroadcast(context,
                0, new Intent(REBOOT_ACTION), 0);

        context.registerReceiver(new RebootRequestReceiver(),
                new IntentFilter(Intent.ACTION_REBOOT),
                android.Manifest.permission.REBOOT, null);

        IntentFilter craveIntentFilter = new IntentFilter();
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_SET_BACKLIGHT);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_TURN_SCREEN_ONOFF);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_REBOOT);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_SHUTDOWN);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_INSTALL_APK);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_REMOVE_APK);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_SET_DATETIME);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_ADB_ENABLE);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_ADB_DISABLE);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_ADB_WIFI_ENABLE);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_ADB_WIFI_DISABLE);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_SET_LOCALE);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_CLEAR_USERDATA);
        craveIntentFilter.addAction(Intent.CRAVEOS_ACTION_CLEAR_CACHE);
        context.registerReceiver(new CraveIntentReceiver(), craveIntentFilter);
        
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.INSTALL_NON_MARKET_APPS, 1);
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
        
        // Add cpu scaling governor check to ensure governor is always set on interactive
        addMonitor(new CpuGovernorCheck());
        
        mBootTime = System.currentTimeMillis();
    }

    public void processStarted(String name, int pid) {
        synchronized (this) {
            if ("com.android.phone".equals(name)) {
                mPhonePid = pid;
            }
        }
    }

    public void addMonitor(Monitor monitor) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Monitors can't be added while the Watchdog is running");
            }
            mMonitors.add(monitor);
        }
    }

    void checkReboot(boolean fromAlarm) {
        int rebootInterval = mReqRebootInterval >= 0 ? mReqRebootInterval
                : REBOOT_DEFAULT_INTERVAL;
        mRebootInterval = rebootInterval;
        if (rebootInterval <= 0) {
            // No reboot interval requested.
            if (localLOGV) Slog.v(TAG, "No need to schedule a reboot alarm!");
            mAlarm.remove(mRebootIntent);
            return;
        }

        long rebootStartTime = mReqRebootStartTime >= 0 ? mReqRebootStartTime
                : REBOOT_DEFAULT_START_TIME;
        long rebootWindowMillis = (mReqRebootWindow >= 0 ? mReqRebootWindow
                : REBOOT_DEFAULT_WINDOW) * 1000;
        long recheckInterval = (mReqRecheckInterval >= 0 ? mReqRecheckInterval
                : MEMCHECK_DEFAULT_RECHECK_INTERVAL) * 1000;

        retrieveBrutalityAmount();

        long realStartTime;
        long now;

        synchronized (this) {
            now = System.currentTimeMillis();
            realStartTime = computeCalendarTime(mCalendar, now,
                    rebootStartTime);

            long rebootIntervalMillis = rebootInterval*24*60*60*1000;
            if (DB || mReqRebootNoWait ||
                    (now-mBootTime) >= (rebootIntervalMillis-rebootWindowMillis)) {
                if (fromAlarm && rebootWindowMillis <= 0) {
                    // No reboot window -- just immediately reboot.
                    EventLog.writeEvent(EventLogTags.WATCHDOG_SCHEDULED_REBOOT, now,
                            (int)rebootIntervalMillis, (int)rebootStartTime*1000,
                            (int)rebootWindowMillis, "");
                    rebootSystem("Checkin scheduled forced");
                    return;
                }

                // Are we within the reboot window?
                if (now < realStartTime) {
                    // Schedule alarm for next check interval.
                    realStartTime = computeCalendarTime(mCalendar,
                            now, rebootStartTime);
                } else if (now < (realStartTime+rebootWindowMillis)) {
                    String doit = shouldWeBeBrutalLocked(now);
                    EventLog.writeEvent(EventLogTags.WATCHDOG_SCHEDULED_REBOOT, now,
                            (int)rebootInterval, (int)rebootStartTime*1000,
                            (int)rebootWindowMillis, doit != null ? doit : "");
                    if (doit == null) {
                        rebootSystem("Checked scheduled range");
                        return;
                    }

                    // Schedule next alarm either within the window or in the
                    // next interval.
                    if ((now+recheckInterval) >= (realStartTime+rebootWindowMillis)) {
                        realStartTime = computeCalendarTime(mCalendar,
                                now + rebootIntervalMillis, rebootStartTime);
                    } else {
                        realStartTime = now + recheckInterval;
                    }
                } else {
                    // Schedule alarm for next check interval.
                    realStartTime = computeCalendarTime(mCalendar,
                            now + rebootIntervalMillis, rebootStartTime);
                }
            }
        }

        if (localLOGV) Slog.v(TAG, "Scheduling next reboot alarm for "
                + ((realStartTime-now)/1000/60) + "m from now");
        mAlarm.remove(mRebootIntent);
        mAlarm.set(AlarmManager.RTC_WAKEUP, realStartTime, mRebootIntent);
    }

    /**
     * Perform a full reboot of the system.
     */
    void rebootSystem(String reason) {
        Slog.i(TAG, "Rebooting system because: " + reason);
        PowerManagerService pms = (PowerManagerService) ServiceManager.getService("power");
        pms.reboot(false, reason, false);
    }

    /**
     * Load the current Gservices settings for when
     * {@link #shouldWeBeBrutalLocked} will allow the brutality to happen.
     * Must not be called with the lock held.
     */
    void retrieveBrutalityAmount() {
        mMinScreenOff = (mReqMinScreenOff >= 0 ? mReqMinScreenOff
                : MEMCHECK_DEFAULT_MIN_SCREEN_OFF) * 1000;
        mMinAlarm = (mReqMinNextAlarm >= 0 ? mReqMinNextAlarm
                : MEMCHECK_DEFAULT_MIN_ALARM) * 1000;
    }

    /**
     * Determine whether it is a good time to kill, crash, or otherwise
     * plunder the current situation for the overall long-term benefit of
     * the world.
     *
     * @param curTime The current system time.
     * @return Returns null if this is a good time, else a String with the
     * text of why it is not a good time.
     */
    String shouldWeBeBrutalLocked(long curTime) {
        if (mBattery == null || !mBattery.isPowered(BatteryManager.BATTERY_PLUGGED_ANY)) {
            return "battery";
        }

        if (mMinScreenOff >= 0 && (mPower == null ||
                mPower.timeSinceScreenWasLastOn() < mMinScreenOff)) {
            return "screen";
        }

        if (mMinAlarm >= 0 && (mAlarm == null ||
                mAlarm.timeToNextAlarm() < mMinAlarm)) {
            return "alarm";
        }

        return null;
    }
    

    void setBacklight(Intent intent) {
    	// Set to manual
    	Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    	
    	if (intent.hasExtra("brightness")) {
    		int brightness = 255;
    		String brightnessStr = null;
    		try {
    			brightnessStr = intent.getStringExtra("brightness");
    		} catch(Exception ex) {
    		}
    		
    		if (brightnessStr == null || brightnessStr.length() == 0) {
    			brightness = intent.getIntExtra("brightness", 100);
    		} else {
    			try {
    				brightness = Integer.parseInt(brightnessStr);
    			} catch(Exception ex) {
    				Slog.e(TAG, "Failed to parse backlight brightness: " + brightnessStr);
    			}
    		}
    		            
            try {
            	//Slog.d(TAG, "setBacklight, brightness: " + brightness);
                IPowerManager power = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
                if (power != null) {
                    power.setTemporaryScreenBrightnessSettingOverride(brightness);
                } else {
                	Slog.w(TAG, "Could not get IPowerManager");
                }

                Settings.System.putInt(mResolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
            } catch (RemoteException doe) {
            }
    	} else {
    		Slog.w(TAG, "setBacklight, missing brightness extra");
    	}
    }
    
    void turnScreenOnOff() {
    	Slog.i(TAG, "Watchdog - TurnScreenOff");
    	
    	try {
    		long now = SystemClock.uptimeMillis();
    		KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0, KeyEvent.META_FUNCTION_ON);
    		InputManager.getInstance().injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    		
    		now = SystemClock.uptimeMillis();
    		KeyEvent up = new KeyEvent(now+1, now+1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0, KeyEvent.META_FUNCTION_ON);
    		InputManager.getInstance().injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        } catch (Exception doe) {
        	Slog.w(TAG, "Exception: " + doe.getMessage());
        }
    }
    
    void setDeviceDateTime(Intent intent) {
    	final AlarmManager alarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
    	boolean isTimeChanged = false;
        
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AUTO_TIME, 0);
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 0);
        
        // Set timezone
        if (intent.hasExtra(Intent.CRAVEOS_EXTRA_DATETIME_TIMEZONEID)) {
	        String timeZoneId = intent.getStringExtra(Intent.CRAVEOS_EXTRA_DATETIME_TIMEZONEID);
	        if (timeZoneId != null) {
	            alarm.setTimeZone(timeZoneId);
	           
	            isTimeChanged = true;
	        }
        }
        
        // Set 24Hour
        if (intent.hasExtra(Intent.CRAVEOS_EXTRA_DATETIME_24HOUR)) {
        	boolean is24Hour = intent.getBooleanExtra(Intent.CRAVEOS_EXTRA_DATETIME_24HOUR, true);
        	Settings.System.putString(mContext.getContentResolver(), Settings.System.TIME_12_24, is24Hour? "24" : "12");
        	
        	isTimeChanged = true;
        }
        
    	// Set date/time
        if (intent.hasExtra(Intent.CRAVEOS_EXTRA_DATETIME_TIMESTAMP)) {
	        long timeStamp = intent.getLongExtra(Intent.CRAVEOS_EXTRA_DATETIME_TIMESTAMP, -1);
	        if (timeStamp > 0) {
	            Calendar c = Calendar.getInstance();
	            c.setTimeInMillis(timeStamp);
	
	            Slog.i(TAG, "setDeviceTime - Hour = " + c.get(Calendar.HOUR) + ", Minutes = " + c.get(Calendar.MINUTE));
	            alarm.setTime(timeStamp);
	            
	            isTimeChanged = true;
	        }
        }

        // Send intent
        if (isTimeChanged) {
        	Intent timeChanged = new Intent(Intent.ACTION_TIME_CHANGED);
        	mContext.sendBroadcastAsUser(timeChanged, UserHandle.ALL);
        }
    }    
    
    void toggleAdb(boolean enabled) {
    	Slog.i(TAG, "toggleAdb, enabled = " + enabled);
    	Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.ADB_ENABLED, (enabled) ? 1 : 0);
    }
    
    void toggleAdbWifi(boolean enabled) {
    	Slog.i(TAG, "toggleAdbWifi, enabled = " + enabled);
    	Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.ADB_PORT, (enabled) ? 5555 : -1);
    }
    
    void setLocale(Intent intent) {
    	String language = intent.getStringExtra(Intent.CRAVEOS_EXTRA_SET_LOCALE);
    	if (language != null && language.length() > 0) {
	    	Locale locale = new Locale(language);
	    	
	    	try {
	            IActivityManager am = ActivityManagerNative.getDefault();
	            Configuration config = am.getConfiguration();
	
	            // Will set userSetLocale to indicate this isn't some passing default - the user
	            // wants this remembered
	            config.setLocale(locale);
	
	            am.updateConfiguration(config);
	            // Trigger the dirty bit for the Settings Provider.
	            BackupManager.dataChanged("com.android.providers.settings");
	        } catch (RemoteException e) {
	            // Intentionally left blank
	        }
    	} else {
    		Slog.w(TAG, "setLocale - No language specifed in Intent");
    	}
    }
    
    void clearAppUserData(String packageName) {
    	if (packageName.isEmpty()) {
    		Slog.w(TAG, "clearAppUserData - packageName is not specified!");
    	} else {
    		ActivityManager am = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
        	boolean res = am.clearApplicationUserData(packageName, null);
        	
        	if (res) {
        		Slog.i(TAG, "clearAppUserData - Cleared userdata for package: " + packageName);
        	} else {
        		Slog.w(TAG, "clearAppUserData - Failed to clear userdata for package: " + packageName);
        	}
    	}
    }
    
    void clearAppCache(String packageName) {
    	if (packageName.isEmpty()) {
    		Slog.w(TAG, "clearAppCache - packageName is not specified!");
    	} else {
    		PackageManager pm = mContext.getPackageManager();
    		pm.deleteApplicationCacheFiles(packageName, null);

    		Slog.i(TAG, "clearAppCache - Cleared cache for package: " + packageName);
    	}
    }

    static long computeCalendarTime(Calendar c, long curTime,
            long secondsSinceMidnight) {

        // start with now
        c.setTimeInMillis(curTime);

        int val = (int)secondsSinceMidnight / (60*60);
        c.set(Calendar.HOUR_OF_DAY, val);
        secondsSinceMidnight -= val * (60*60);
        val = (int)secondsSinceMidnight / 60;
        c.set(Calendar.MINUTE, val);
        c.set(Calendar.SECOND, (int)secondsSinceMidnight - (val*60));
        c.set(Calendar.MILLISECOND, 0);

        long newTime = c.getTimeInMillis();
        if (newTime < curTime) {
            // The given time (in seconds since midnight) has already passed for today, so advance
            // by one day (due to daylight savings, etc., the delta may differ from 24 hours).
            c.add(Calendar.DAY_OF_MONTH, 1);
            newTime = c.getTimeInMillis();
        }

        return newTime;
    }

    @Override
    public void run() {
        boolean waitedHalf = false;
        while (true) {
            mCompleted = false;
            mHandler.sendEmptyMessage(MONITOR);

            synchronized (this) {
                long timeout = TIME_TO_WAIT;

                // NOTE: We use uptimeMillis() here because we do not want to increment the time we
                // wait while asleep. If the device is asleep then the thing that we are waiting
                // to timeout on is asleep as well and won't have a chance to run, causing a false
                // positive on when to kill things.
                long start = SystemClock.uptimeMillis();
                while (timeout > 0 && !mForceKillSystem) {
                    try {
                        wait(timeout);  // notifyAll() is called when mForceKillSystem is set
                    } catch (InterruptedException e) {
                        Log.wtf(TAG, e);
                    }
                    timeout = TIME_TO_WAIT - (SystemClock.uptimeMillis() - start);
                }

                if (mCompleted && !mForceKillSystem) {
                    // The monitors have returned.
                    waitedHalf = false;
                    continue;
                }

                if (!waitedHalf) {
                    // We've waited half the deadlock-detection interval.  Pull a stack
                    // trace and wait another half.
                    ArrayList<Integer> pids = new ArrayList<Integer>();
                    pids.add(Process.myPid());
                    ActivityManagerService.dumpStackTraces(true, pids, null, null,
                            NATIVE_STACKS_OF_INTEREST);
                    waitedHalf = true;
                    continue;
                }
            }

            // If we got here, that means that the system is most likely hung.
            // First collect stack traces from all threads of the system process.
            // Then kill this process so that the system will restart.

            final String name = (mCurrentMonitor != null) ?
                    mCurrentMonitor.getClass().getName() : "null";
            EventLog.writeEvent(EventLogTags.WATCHDOG, name);

            ArrayList<Integer> pids = new ArrayList<Integer>();
            pids.add(Process.myPid());
            if (mPhonePid > 0) pids.add(mPhonePid);
            // Pass !waitedHalf so that just in case we somehow wind up here without having
            // dumped the halfway stacks, we properly re-initialize the trace file.
            final File stack = ActivityManagerService.dumpStackTraces(
                    !waitedHalf, pids, null, null, NATIVE_STACKS_OF_INTEREST);

            // Give some extra time to make sure the stack traces get written.
            // The system's been hanging for a minute, another second or two won't hurt much.
            SystemClock.sleep(2000);

            // Pull our own kernel thread stacks as well if we're configured for that
            if (RECORD_KERNEL_THREADS) {
                dumpKernelStackTraces();
            }

            // Trigger the kernel to dump all blocked threads to the kernel log
            try {
                FileWriter sysrq_trigger = new FileWriter("/proc/sysrq-trigger");
                sysrq_trigger.write("w");
                sysrq_trigger.close();
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write to /proc/sysrq-trigger");
                Slog.e(TAG, e.getMessage());
            }

            // Try to add the error to the dropbox, but assuming that the ActivityManager
            // itself may be deadlocked.  (which has happened, causing this statement to
            // deadlock and the watchdog as a whole to be ineffective)
            Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
                    public void run() {
                        mActivity.addErrorToDropBox(
                                "watchdog", null, "system_server", null, null,
                                name, null, stack, null);
                    }
                };
            dropboxThread.start();
            try {
                dropboxThread.join(2000);  // wait up to 2 seconds for it to return.
            } catch (InterruptedException ignored) {}

            // Only kill the process if the debugger is not attached.
            if (!Debug.isDebuggerConnected()) {
                Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + name);
                Process.killProcess(Process.myPid());
                System.exit(10);
            } else {
                Slog.w(TAG, "Debugger connected: Watchdog is *not* killing the system process");
            }

            waitedHalf = false;
        }
    }

    private File dumpKernelStackTraces() {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        native_dumpKernelStacks(tracesPath);
        return new File(tracesPath);
    }

    private native void native_dumpKernelStacks(String tracesPath);
}
