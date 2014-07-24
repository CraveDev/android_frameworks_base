package com.android.systemui.statusbar.crave;

import com.android.systemui.BatteryMeterView.BatteryMeterMode;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;

import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.LevelListDrawable;
import android.os.BatteryManager;
import android.util.AttributeSet;
import android.widget.ImageView;

public class CraveBattery extends ImageView implements BatteryController.BatteryStateChangeCallback {    
    private int mBatteryLevel = 0;
    private int mBatteryStatus;
    private boolean mBatteryPluggedIn = false;
    
    AnimationDrawable mChargeAnimation;
    
	public CraveBattery(Context context) {
        this(context, null);
    }

    public CraveBattery(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
	    
	public CraveBattery(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}
    
    private synchronized void updateTile() {
        setImageLevel(mBatteryLevel);
        
        if (mChargeAnimation != null) {
    		mChargeAnimation.stop();
    		mChargeAnimation = null;
    	}
        
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || mBatteryPluggedIn) {
        	setImageDrawable(mContext.getResources().getDrawable(R.drawable.crave_battery_charging));
        	setImageLevel(mBatteryLevel);
        	
        	LevelListDrawable chargeAnimList = (LevelListDrawable)getDrawable();
	        mChargeAnimation = (AnimationDrawable)chargeAnimList.getCurrent();
			mChargeAnimation.start();
        } else {
        	setImageDrawable(mContext.getResources().getDrawable(R.drawable.crave_battery));
        }
    }

	@Override
	public void onBatteryLevelChanged(boolean present, int level,
			boolean pluggedIn, int status) {
		mBatteryLevel = level;
        mBatteryStatus = status;
        mBatteryPluggedIn = pluggedIn;
        updateTile();
	}

	@Override
	public void onBatteryMeterModeChanged(BatteryMeterMode mode) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onBatteryMeterShowPercent(boolean showPercent) {
		// TODO Auto-generated method stub
	}

}
