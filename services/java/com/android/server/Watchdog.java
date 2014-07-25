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

import android.app.IActivityController;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;

import com.android.server.am.ActivityManagerService;
import com.android.server.power.PowerManagerService;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
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

    static final long DEFAULT_TIMEOUT = DB ? 10*1000 : 60*1000;
    static final long CHECK_INTERVAL = DEFAULT_TIMEOUT / 2;

    // These are temporally ordered: larger values as lateness increases
    static final int COMPLETED = 0;
    static final int WAITING = 1;
    static final int WAITED_HALF = 2;
    static final int OVERDUE = 3;

    // Which native processes to dump into dropbox's stack traces
    public static final String[] NATIVE_STACKS_OF_INTEREST = new String[] {
        "/system/bin/mediaserver",
        "/system/bin/sdcard",
        "/system/bin/surfaceflinger"
    };

    static Watchdog sWatchdog;

    /* This handler will be used to post message back onto the main thread */
    final ArrayList<HandlerChecker> mHandlerCheckers = new ArrayList<HandlerChecker>();
    final HandlerChecker mMonitorChecker;
    ContentResolver mResolver;
    BatteryService mBattery;
    PowerManagerService mPower;
    AlarmManagerService mAlarm;
    ActivityManagerService mActivity;

    int mPhonePid;
    IActivityController mController;
    boolean mAllowRestart = true;
    int mActivityControllerPid;

    Context mContext;
    
    /**
     * Used for checking status of handle threads and scheduling monitor callbacks.
     */
    public final class HandlerChecker implements Runnable {
        private final Handler mHandler;
        private final String mName;
        private final long mWaitMax;
        private final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
        private boolean mCompleted;
        private Monitor mCurrentMonitor;
        private long mStartTime;

        HandlerChecker(Handler handler, String name, long waitMaxMillis) {
            mHandler = handler;
            mName = name;
            mWaitMax = waitMaxMillis;
            mCompleted = true;
        }

        public void addMonitor(Monitor monitor) {
            mMonitors.add(monitor);
        }

        public void scheduleCheckLocked() {
            if (mMonitors.size() == 0 && mHandler.getLooper().isIdling()) {
                // If the target looper is or just recently was idling, then
                // there is no reason to enqueue our checker on it since that
                // is as good as it not being deadlocked.  This avoid having
                // to do a context switch to check the thread.  Note that we
                // only do this if mCheckReboot is false and we have no
                // monitors, since those would need to be executed at this point.
                mCompleted = true;
                return;
            }

            if (!mCompleted) {
                // we already have a check in flight, so no need
                return;
            }

            mCompleted = false;
            mCurrentMonitor = null;
            mStartTime = SystemClock.uptimeMillis();
            mHandler.postAtFrontOfQueue(this);
        }

        public boolean isOverdueLocked() {
            return (!mCompleted) && (SystemClock.uptimeMillis() > mStartTime + mWaitMax);
        }

        public int getCompletionStateLocked() {
            if (mCompleted) {
                return COMPLETED;
            } else {
                long latency = SystemClock.uptimeMillis() - mStartTime;
                if (latency < mWaitMax/2) {
                    return WAITING;
                } else if (latency < mWaitMax) {
                    return WAITED_HALF;
                }
            }
            return OVERDUE;
        }

        public Thread getThread() {
            return mHandler.getLooper().getThread();
        }

        public String getName() {
            return mName;
        }

        public String describeBlockedStateLocked() {
            if (mCurrentMonitor == null) {
                return "Blocked in handler on " + mName + " (" + getThread().getName() + ")";
            } else {
                return "Blocked in monitor " + mCurrentMonitor.getClass().getName()
                        + " on " + mName + " (" + getThread().getName() + ")";
            }
        }

        @Override
        public void run() {
            final int size = mMonitors.size();
            for (int i = 0 ; i < size ; i++) {
                synchronized (Watchdog.this) {
                    mCurrentMonitor = mMonitors.get(i);
                }
                mCurrentMonitor.monitor();
            }

            synchronized (Watchdog.this) {
                mCompleted = true;
                mCurrentMonitor = null;
            }
        }
    }

    final class RebootRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent.getIntExtra("nowait", 0) != 0) {
                rebootSystem("Received ACTION_REBOOT broadcast");
                return;
            }
            Slog.w(TAG, "Unsupported ACTION_REBOOT broadcast: " + intent);
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
        // Initialize handler checkers for each common thread we want to check.  Note
        // that we are not currently checking the background thread, since it can
        // potentially hold longer running operations with no guarantees about the timeliness
        // of operations there.

        // The shared foreground thread is the main checker.  It is where we
        // will also dispatch monitor checks and do other work.
        mMonitorChecker = new HandlerChecker(FgThread.getHandler(),
                "foreground thread", DEFAULT_TIMEOUT);
        mHandlerCheckers.add(mMonitorChecker);
        // Add checker for main thread.  We only do a quick check since there
        // can be UI running on the thread.
        mHandlerCheckers.add(new HandlerChecker(new Handler(Looper.getMainLooper()),
                "main thread", DEFAULT_TIMEOUT));
        // Add checker for shared UI thread.
        mHandlerCheckers.add(new HandlerChecker(UiThread.getHandler(),
                "ui thread", DEFAULT_TIMEOUT));
        // And also check IO thread.
        mHandlerCheckers.add(new HandlerChecker(IoThread.getHandler(),
                "i/o thread", DEFAULT_TIMEOUT));
    }

    public void init(Context context, BatteryService battery,
            PowerManagerService power, AlarmManagerService alarm,
            ActivityManagerService activity) {
        mResolver = context.getContentResolver();
        mBattery = battery;
        mPower = power;
        mAlarm = alarm;
        mActivity = activity;
        mContext = context;

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
    }

    public void processStarted(String name, int pid) {
        synchronized (this) {
            if ("com.android.phone".equals(name)) {
                mPhonePid = pid;
            }
            else if ("ActivityController".equals(name)) {
                     mActivityControllerPid = pid;
            }
        }
    }

    public void setActivityController(IActivityController controller) {
        synchronized (this) {
            mController = controller;
        }
    }

    public void setAllowRestart(boolean allowRestart) {
        synchronized (this) {
            mAllowRestart = allowRestart;
        }
    }

    public void addMonitor(Monitor monitor) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Monitors can't be added once the Watchdog is running");
            }
            mMonitorChecker.addMonitor(monitor);
        }
    }

    public void addThread(Handler thread, String name) {
        addThread(thread, name, DEFAULT_TIMEOUT);
    }

    public void addThread(Handler thread, String name, long timeoutMillis) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Threads can't be added once the Watchdog is running");
            }
            mHandlerCheckers.add(new HandlerChecker(thread, name, timeoutMillis));
        }
    }

    /**
     * Perform a full reboot of the system.
     */
    void rebootSystem(String reason) {
        Slog.i(TAG, "Rebooting system because: " + reason);
        PowerManagerService pms = (PowerManagerService) ServiceManager.getService("power");
        pms.reboot(false, reason, false);
    }

    private int evaluateCheckerCompletionLocked() {
        int state = COMPLETED;
        for (int i=0; i<mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i);
            state = Math.max(state, hc.getCompletionStateLocked());
        }
        return state;
    }

    private ArrayList<HandlerChecker> getBlockedCheckersLocked() {
        ArrayList<HandlerChecker> checkers = new ArrayList<HandlerChecker>();
        for (int i=0; i<mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i);
            if (hc.isOverdueLocked()) {
                checkers.add(hc);
            }
        }
        return checkers;
    }

    private String describeCheckersLocked(ArrayList<HandlerChecker> checkers) {
        StringBuilder builder = new StringBuilder(128);
        for (int i=0; i<checkers.size(); i++) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(checkers.get(i).describeBlockedStateLocked());
        }
        return builder.toString();
    }

    @Override
    public void run() {
        boolean waitedHalf = false;
        while (true) {
            final ArrayList<HandlerChecker> blockedCheckers;
            final String subject;
            final boolean allowRestart;
            synchronized (this) {
                long timeout = CHECK_INTERVAL;
                // Make sure we (re)spin the checkers that have become idle within
                // this wait-and-check interval
                for (int i=0; i<mHandlerCheckers.size(); i++) {
                    HandlerChecker hc = mHandlerCheckers.get(i);
                    hc.scheduleCheckLocked();
                }

                // NOTE: We use uptimeMillis() here because we do not want to increment the time we
                // wait while asleep. If the device is asleep then the thing that we are waiting
                // to timeout on is asleep as well and won't have a chance to run, causing a false
                // positive on when to kill things.
                long start = SystemClock.uptimeMillis();
                while (timeout > 0) {
                    try {
                        wait(timeout);
                    } catch (InterruptedException e) {
                        Log.wtf(TAG, e);
                    }
                    timeout = CHECK_INTERVAL - (SystemClock.uptimeMillis() - start);
                }

                final int waitState = evaluateCheckerCompletionLocked();
                if (waitState == COMPLETED) {
                    // The monitors have returned; reset
                    waitedHalf = false;
                    continue;
                } else if (waitState == WAITING) {
                    // still waiting but within their configured intervals; back off and recheck
                    continue;
                } else if (waitState == WAITED_HALF) {
                    if (!waitedHalf) {
                        // We've waited half the deadlock-detection interval.  Pull a stack
                        // trace and wait another half.
                        ArrayList<Integer> pids = new ArrayList<Integer>();
                        pids.add(Process.myPid());
                        ActivityManagerService.dumpStackTraces(true, pids, null, null,
                                NATIVE_STACKS_OF_INTEREST);
                        waitedHalf = true;
                    }
                    continue;
                }

                // something is overdue!
                blockedCheckers = getBlockedCheckersLocked();
                subject = describeCheckersLocked(blockedCheckers);
                allowRestart = mAllowRestart;
            }

            // If we got here, that means that the system is most likely hung.
            // First collect stack traces from all threads of the system process.
            // Then kill this process so that the system will restart.
            EventLog.writeEvent(EventLogTags.WATCHDOG, subject);

            ArrayList<Integer> pids = new ArrayList<Integer>();
            pids.add(Process.myPid());
            if (mPhonePid > 0) pids.add(mPhonePid);
            if (mActivityControllerPid > 0) pids.add(mActivityControllerPid);
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

            String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
            if (tracesPath != null && tracesPath.length() != 0) {
                File traceRenameFile = new File(tracesPath);
                String newTracesPath;
                int lpos = tracesPath.lastIndexOf (".");
                if (-1 != lpos)
                    newTracesPath = tracesPath.substring (0, lpos) + "_SystemServer_WDT" + tracesPath.substring (lpos);
                else
                    newTracesPath = tracesPath + "_SystemServer_WDT";
                traceRenameFile.renameTo(new File(newTracesPath));
                tracesPath = newTracesPath;
            }

            final File newFd = new File(tracesPath);

            // Try to add the error to the dropbox, but assuming that the ActivityManager
            // itself may be deadlocked.  (which has happened, causing this statement to
            // deadlock and the watchdog as a whole to be ineffective)
            Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
                    public void run() {
                        mActivity.addErrorToDropBox(
                                "watchdog", null, "system_server", null, null,
                                subject, null, newFd, null);
                    }
                };
            dropboxThread.start();
            try {
                dropboxThread.join(2000);  // wait up to 2 seconds for it to return.
            } catch (InterruptedException ignored) {}

            IActivityController controller;
            synchronized (this) {
                controller = mController;
            }
            if (controller != null) {
                Slog.i(TAG, "Reporting stuck state to activity controller");
                try {
                    Binder.setDumpDisabled("Service dumps disabled due to hung system process.");
                    // 1 = keep waiting, -1 = kill system
                    int res = controller.systemNotResponding(subject);
                    if (res >= 0) {
                        Slog.i(TAG, "Activity controller requested to coninue to wait");
                        waitedHalf = false;
                        continue;
                    }
                } catch (RemoteException e) {
                }
            }

            // Only kill the process if the debugger is not attached.
            if (Debug.isDebuggerConnected()) {
                Slog.w(TAG, "Debugger connected: Watchdog is *not* killing the system process");
            } else if (!allowRestart) {
                Slog.w(TAG, "Restart not allowed: Watchdog is *not* killing the system process");
            } else {
                Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + subject);
                for (int i=0; i<blockedCheckers.size(); i++) {
                    Slog.w(TAG, blockedCheckers.get(i).getName() + " stack trace:");
                    StackTraceElement[] stackTrace
                            = blockedCheckers.get(i).getThread().getStackTrace();
                    for (StackTraceElement element: stackTrace) {
                        Slog.w(TAG, "    at " + element);
                    }
                }
                Slog.w(TAG, "*** GOODBYE!");
                Process.killProcess(Process.myPid());
                System.exit(10);
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
}
