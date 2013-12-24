package com.android.systemui.statusbar.crave;

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
    
    @Override
    public void onBatteryLevelChanged(int level, int status) {
        mBatteryLevel = level;
        mBatteryStatus = status;
        updateTile();
    }
    
    private synchronized void updateTile() {
        setImageLevel(mBatteryLevel);
        
        if (mChargeAnimation != null) {
    		mChargeAnimation.stop();
    		mChargeAnimation = null;
    	}
        
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
        	setImageDrawable(mContext.getResources().getDrawable(R.drawable.crave_battery_charging));
        	setImageLevel(mBatteryLevel);
        	
        	LevelListDrawable chargeAnimList = (LevelListDrawable)getDrawable();
	        mChargeAnimation = (AnimationDrawable)chargeAnimList.getCurrent();
			mChargeAnimation.start();
        } else {
        	setImageDrawable(mContext.getResources().getDrawable(R.drawable.crave_battery));
        }
    }

}
