package com.android.systemui.statusbar.crave;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.service.notification.StatusBarNotification;
import android.util.Slog;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;

public class CraveStatusBar extends BaseStatusBar {
	static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;
    
	public static final int MSG_ADD_COMPONENT = 1000;
	public static final int MSG_REMOVE_COMPONENT = 1001;
	public static final int MSG_CLEAR_NAVBAR = 1002;
	public static final int MSG_TOGGLE_VISIBILITY = 1003;
	public static final int MSG_RESET_NAVBAR = 1004;
	public static final int MSG_TOGGLE_ENABLE = 1005;
	public static final int MSG_UPDATE_COMPONENT = 1006;
	
    CraveStatusBarView mCraveStatusBarView;
    
    @Override
    public void start() {
    	super.start(); // calls createAndAddWindows()
    	
		mWindowManager.addView(mCraveStatusBarView, getNavigationBarLayoutParams());
		
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_ADD);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_REMOVE);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_UPDATE);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_CLEAR);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_SET_VISIBILITY);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_SET_ENABLED);
		filter.addAction(Intent.CRAVEOS_NAVBAR_ACTION_RESET);
		mContext.registerReceiver(new CraveStatusBarReceiver(), filter);
    }

	@Override
	public void addIcon(String slot, int index, int viewIndex,
			StatusBarIcon icon) {
		
	}

	@Override
	public void updateIcon(String slot, int index, int viewIndex,
			StatusBarIcon old, StatusBarIcon icon) {
		
	}

	@Override
	public void removeIcon(String slot, int index, int viewIndex) {
		
	}

	@Override
	public void addNotification(IBinder key, StatusBarNotification notification) {
		
	}

	@Override
	public void removeNotification(IBinder key) {
		
	}

	@Override
	public void disable(int state) {
		
	}

	@Override
	public void animateExpandNotificationsPanel() {
		
	}

	@Override
	public void animateCollapsePanels(int flags) {
		
	}

	@Override
	public void animateExpandSettingsPanel() {
		
	}

	@Override
	public void setSystemUiVisibility(int vis, int mask) {
		
	}

	@Override
	public void topAppWindowChanged(boolean visible) {
		
	}

	@Override
	public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
		
	}

	@Override
	public void setHardKeyboardStatus(boolean available, boolean enabled) {
		
	}

	@Override
	public void setWindowState(int window, int state) {
		
	}

	@Override
	protected void createAndAddWindows() {
		makeStatusBar();		
	}

	@Override
	protected void refreshLayout(int layoutDirection) {
		
	}
	
	@Override
    public synchronized void showHideStatusBar(boolean hide) {
    	if (mIsHidden != hide) {
    		super.showHideStatusBar(hide);
	    	
	    	if (mCraveStatusBarView != null) {
	    		try {
		    		if (hide) {
		    			mWindowManager.removeView(mCraveStatusBarView);
		    		} else {
		    			mWindowManager.addView(mCraveStatusBarView, getNavigationBarLayoutParams());
		    			mCraveStatusBarView.updateDefaultComponents();
		    		}
	    		} catch(RuntimeException ex) {
	    			Slog.e(TAG, "Exception in showHideStatusBar: " + ex.getMessage());
	    			mIsHidden = !hide;
	    		}
	    	}
    	}
    }

	@Override
	protected LayoutParams getSearchLayoutParams(
			android.view.ViewGroup.LayoutParams layoutParams) {
		boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.setTitle("SearchPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
	}

	@Override
	protected View getStatusBarView() {
		return mCraveStatusBarView;
	}

	@Override
	public void resetHeadsUpDecayTimer() {
		
	}

	@Override
	public void hideHeadsUp() {
		
	}

	@Override
	protected void haltTicker() {
		
	}

	@Override
	protected void setAreThereNotifications() {
		
	}

	@Override
	protected void updateNotificationIcons() {
		
	}

	@Override
	protected void tick(IBinder key, StatusBarNotification n, boolean firstTime) {
		
	}

	@Override
	protected void updateExpandedViewPos(int expandedPosition) {
		
	}

	@Override
	protected int getExpandedViewMaxHeight() {
		return 0;
	}

	@Override
	protected boolean isNotificationPanelFullyVisible() {
		return false;
	}

	@Override
	protected boolean isTrackingNotificationPanel() {
		return false;
	}

	@Override
	protected boolean shouldDisableNavbarGestures() {
		return false;
	}

	private void makeStatusBar() {
		final Context context = mContext;
		mCraveStatusBarView = (CraveStatusBarView) View.inflate(context, R.layout.crave_navigation_bar, null);
		mCraveStatusBarView.initialize();
	}
	
	private WindowManager.LayoutParams getNavigationBarLayoutParams() {		
		WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
				LayoutParams.MATCH_PARENT, 
				LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
		
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("CraveNavigationBar");
        lp.windowAnimations = 0;
        return lp;
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new CraveStatusBar.H();
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends BaseStatusBar.H {
        @Override
        public void handleMessage(Message m) {
            super.handleMessage(m);
            switch (m.what) {
            case MSG_ADD_COMPONENT:
            	addComponent(m.getData());
            	break;
            case MSG_REMOVE_COMPONENT:
            	removeComponent(m.getData());
            	break;
            case MSG_CLEAR_NAVBAR:
            	clearNavigationBar();
            	break;
            case MSG_TOGGLE_VISIBILITY:
            	toggleComponentVisiblity(m.getData());
            	break;
            case MSG_TOGGLE_ENABLE:
            	toggleComponentEnable(m.getData());
            	break;
            case MSG_UPDATE_COMPONENT:
            	updateComponent(m.getData());
            	break;
            }
        }
    }

    public Handler getHandler() {
        return mHandler;
    }
	
	private void addComponent(Bundle data) {
		byte[] icon = null;
		String text = "";
		String key = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_KEY, "");
		String action = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_ACTION, "");
		
		if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_ICON)) {
			icon = data.getByteArray(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_ICON);
		} else if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_TEXT)) {
			text = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_TEXT);
		}
		
		int type = data.getInt(Intent.CRAVEOS_NAVBAR_EXTRA_TYPE, 2); // Default = button
		
		Bundle containerBundle = data.getBundle(Intent.CRAVEOS_NAVBAR_EXTRA_CONTAINER);
		CraveContainer container;
		if (containerBundle == null) {
			container = new CraveContainer();
		} else {
			container = new CraveContainer(containerBundle);
		}
			
		mCraveStatusBarView.addComponent(key, type, text, icon, action, container);
	}
	
	private void updateComponent(Bundle data) {
		byte[] icon = null;
		String text = "";
		String key = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_KEY, "");
		
		if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_ICON)) {
			icon = data.getByteArray(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_ICON);
		} else if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_TEXT)) {
			text = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_ADD_TEXT);
		}
		
		mCraveStatusBarView.updateComponent(key, text, icon);
		
		if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_ENABLED))
			mCraveStatusBarView.toggleComponentEnable(key, data.getBoolean(Intent.CRAVEOS_NAVBAR_EXTRA_ENABLED));
		
		if (data.containsKey(Intent.CRAVEOS_NAVBAR_EXTRA_VISIBILITY))
			mCraveStatusBarView.toggleComponentVisibility(key, data.getInt(Intent.CRAVEOS_NAVBAR_EXTRA_VISIBILITY));		
	}
	
	private void removeComponent(Bundle data) {
		String key = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_KEY, "");
		mCraveStatusBarView.removeComponent(key);
	}
	
	private void clearNavigationBar() {
		mCraveStatusBarView.clearCustomComponents();
	}
	
	private void toggleComponentVisiblity(Bundle data) {
		String key = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_KEY); 
		int visibility = data.getInt(Intent.CRAVEOS_NAVBAR_EXTRA_VISIBILITY, View.VISIBLE);
		
		mCraveStatusBarView.toggleComponentVisibility(key, visibility);
	}
	
	private void toggleComponentEnable(Bundle data) {
		String key = data.getString(Intent.CRAVEOS_NAVBAR_EXTRA_KEY); 
		boolean enabled = data.getBoolean(Intent.CRAVEOS_NAVBAR_EXTRA_ENABLED, true);
		
		mCraveStatusBarView.toggleComponentEnable(key, enabled);
	}
	
	public class CraveStatusBarReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			
			Slog.v(TAG, "CraveStatusBar - action=" + action);
			
			if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_ADD)) {
				if (!intent.hasExtra(Intent.CRAVEOS_NAVBAR_EXTRA_KEY)) {
					Slog.e(TAG, "Missing key");
					return;
				}
				
				Message msg = new Message();
				msg.what = MSG_ADD_COMPONENT;
				msg.setData(intent.getExtras());
				getHandler().dispatchMessage(msg);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_REMOVE)) {
				if (!intent.hasExtra(Intent.CRAVEOS_NAVBAR_EXTRA_KEY)) {
					Slog.e(TAG, "Missing key");
					return;
				}
				
				Message msg = new Message();
				msg.what = MSG_REMOVE_COMPONENT;
				msg.setData(intent.getExtras());
				getHandler().sendMessage(msg);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_CLEAR)) {
				getHandler().sendEmptyMessage(MSG_CLEAR_NAVBAR);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_SET_VISIBILITY)) {
				if (!intent.hasExtra(Intent.CRAVEOS_NAVBAR_EXTRA_KEY)) {
					Slog.e(TAG, "Missing key");
					return;
				}
				
				Message msg = new Message();
				msg.what = MSG_TOGGLE_VISIBILITY;
				msg.setData(intent.getExtras());
				getHandler().sendMessage(msg);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_RESET)) {
				getHandler().sendEmptyMessage(MSG_RESET_NAVBAR);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_SET_ENABLED)) {
				if (!intent.hasExtra(Intent.CRAVEOS_NAVBAR_EXTRA_KEY)) {
					Slog.e(TAG, "Missing key");
					return;
				}
				
				Message msg = new Message();
				msg.what = MSG_TOGGLE_ENABLE;
				msg.setData(intent.getExtras());
				getHandler().sendMessage(msg);
			} else if (action.equals(Intent.CRAVEOS_NAVBAR_ACTION_UPDATE)) {
				if (!intent.hasExtra(Intent.CRAVEOS_NAVBAR_EXTRA_KEY)) {
					Slog.e(TAG, "Missing key");
					return;
				}
				
				Message msg = new Message();
				msg.what = MSG_UPDATE_COMPONENT;
				msg.setData(intent.getExtras());
				getHandler().sendMessage(msg);
			}
		}
	}
}
