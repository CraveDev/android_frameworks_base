package com.android.systemui.statusbar.crave;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar.NavigationBarCallback;
import com.android.systemui.statusbar.NavigationButtons.ButtonInfo;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.CircleBattery;
import com.android.systemui.statusbar.policy.KeyButtonView;

public class CraveStatusBarView extends FrameLayout implements NavigationBarCallback, View.OnClickListener {
	private static final String TAG = "CraveStatusBarView";
	private static final boolean DEBUG = true;
	
	private static final String HOME_STRING = "home";
    private static final String BACK_STRING = "back";
    
    public static final ButtonInfo HOME = new ButtonInfo(
            R.string.navbar_home_button,
            R.string.accessibility_home, KeyEvent.KEYCODE_HOME, R.drawable.ic_sysbar_home,
            R.drawable.ic_sysbar_home_land, R.drawable.ic_sysbar_home, HOME_STRING);
	public static final ButtonInfo BACK =  new ButtonInfo(
            R.string.navbar_back_button, R.string.accessibility_back,
            KeyEvent.KEYCODE_BACK, R.drawable.ic_sysbar_back,
            R.drawable.ic_sysbar_back_land, R.drawable.ic_sysbar_back_side, BACK_STRING);
	
	public static Typeface TypefaceRegular;
	public static Typeface TypefaceMedium;
	public static Typeface TypefaceDigital;
	
	HashMap<String, ComponentContainer> mComponentMap = new HashMap<String, ComponentContainer>();
	
	ImageView mManagementButton;
	LinearLayout mLeftContainer;
	LinearLayout mRightContainer;
	
	BatteryController mBatteryController;
	
	CraveClock mClock;
	CraveBattery mBattery;
	
	class ComponentContainer {
		View view;
		int position;
		String action;
		boolean isCustom;
		
		ComponentContainer(View v, int pos) {
			view = v;
			position = pos;
		}
	}

	public CraveStatusBarView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}
	
	public void initialize() {
		TypefaceRegular = Typeface.createFromAsset(mContext.getAssets(), "fonts/KievitOT-Regular.otf");
		if (TypefaceRegular == null)
			Slog.e(TAG, "Failed to load regular typeface");
		
		TypefaceMedium = Typeface.createFromAsset(mContext.getAssets(), "fonts/KievitOT-Medium.otf");
		if (TypefaceMedium == null)
			Slog.e(TAG, "Failed to load medium typeface");
		
		TypefaceDigital = Typeface.createFromAsset(mContext.getAssets(), "fonts/KievitOTLF-Medium.otf");
		if (TypefaceDigital == null)
			Slog.e(TAG, "Failed to load medium digital typeface");
		
		mLeftContainer = (LinearLayout)findViewById(R.id.leftArea);
		mRightContainer = (LinearLayout)findViewById(R.id.rightArea);
		
		mManagementButton = (ImageView)findViewById(R.id.managementButton);
		mManagementButton.setOnClickListener(this);
		
		loadDefaultComponents();
	}
	
	private void loadDefaultComponents() {
		// Back button
		KeyButtonView btnBack = (KeyButtonView)findViewById(R.id.one);
		btnBack.setInfo(BACK, false, false);
		ComponentContainer container = new ComponentContainer(btnBack, -1);
		container.isCustom = false;
		mComponentMap.put(BACK_STRING, container);
		
		// Home button
		KeyButtonView btnHome = (KeyButtonView)findViewById(R.id.two);
		btnHome.setInfo(HOME, false, false);
		container = new ComponentContainer(btnHome, -1);
		container.isCustom = false;
		mComponentMap.put(HOME_STRING, container); 
		
		// Crave Clock
		mClock = (CraveClock)findViewById(R.id.craveClock); 
		mClock.setTypeface(TypefaceDigital);
		mClock.setTextSize(28);
		
		// Crave battery
		mBatteryController = new BatteryController(mContext);
		
		mBattery = (CraveBattery)findViewById(R.id.craveBattery);
        mBatteryController.addStateChangedCallback(mBattery);
	}

	@Override
	public void setNavigationIconHints(int hints) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setMenuVisibility(boolean showMenu) {
		// Empty

	}

	@Override
	public void setDisabledFlags(int disabledFlags) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onClick(View v) {
		if (v.getId() == R.id.managementButton) {
			getContext().sendBroadcast(new Intent(Intent.CRAVEOS_NAVBAR_ACTION_MANAGEMENT_BUTTON));
		} else {
			if (v.getTag() != null) {
				String key = (String)v.getTag();
				
				if (DEBUG)
					Slog.d(TAG, "onClick - Key: " + key);
				
				ComponentContainer container = mComponentMap.get(key);
				if (container.action.length() > 0)
					mContext.sendBroadcast(new Intent(container.action));
			}
		}
	}
	
	public void toggleComponentVisibility(String key, int visible) {
		if (mComponentMap.containsKey(key)) {
			ComponentContainer component = mComponentMap.get(key);
			
			if (component.view != null) {
				component.view.setVisibility(visible);
			}
		}
	}
	
	public void clearCustomComponents() {
		Set<Entry<String, ComponentContainer>> components = new HashSet<Entry<String,ComponentContainer>>(mComponentMap.entrySet());
		for(Entry<String, ComponentContainer> entry : components) {
			ComponentContainer component = entry.getValue();
			if (component.isCustom) {
				mComponentMap.remove(entry.getKey());
				
				if (component.position == -1) {
					mLeftContainer.removeView(component.view);
				} else if (component.position == 1) {
					mRightContainer.removeView(component.view);
				}
			}
		}
	}
	
	public void addComponent(String key, String text, byte[] icon, String action, int position) {
		addComponent(key, text, icon, action, position, true);
	}
	
	private void addComponent(String key, String text, byte[] icon, String action, int position, boolean isCustom) {
		if (mComponentMap.containsKey(key)) {
			Slog.w(TAG, "View with key (" + key + ") already exists. Igorning this component.");
			return;
		}
		
		if (DEBUG)
			Slog.d(TAG, "Added new icon (key=" + key + 
					", text=" + text + 
					", icon=" + ((icon == null) ? "null" : "available") + 
					", action=" + action +
					", position=" + position + ")");
		
		LinearLayout view = createContainer(key);
		if (icon != null) {
			view.addView(createIcon(icon));
		} else {			
			view.addView(createButton(text));
		}
		
		ComponentContainer container = new ComponentContainer(view, position);
		container.isCustom = isCustom;
		
		if (action.length() > 0) {
			container.view.setOnClickListener(this);
			container.action = action;
		}
		
		if (position == -1)
			mLeftContainer.addView(container.view);
		else if (position == 1) {
			mRightContainer.addView(container.view, 0);
		}
		
		mComponentMap.put(key, container);
	}
	
	public void removeComponent(String key) {
		if (mComponentMap.containsKey(key) && mComponentMap.get(key).isCustom) {
			if (DEBUG)
				Slog.d(TAG, "Removing component with key: " + key);
			
			ComponentContainer container = mComponentMap.remove(key);
			if (container != null) {
				if (container.position == -1)
					mLeftContainer.removeView(container.view);
				else if (container.position == 1)
					mRightContainer.removeView(container.view);
			}
		}
	}
	
	private ImageView createIcon(byte[] icon) {		
		Bitmap bmp = BitmapFactory.decodeByteArray(icon, 0, icon.length);
		ImageView img = new ImageView(getContext());
		img.setImageBitmap(bmp);
		img.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		
		return img;
	}
	
	private Button createButton(String text) {
		Button btn = new Button(getContext());
		btn.setTextColor(mContext.getResources().getColorStateList(R.color.button_text));
		btn.setTypeface(TypefaceMedium);
		btn.setTextSize(22);
		btn.setBackgroundResource(R.drawable.crave_nav_button);
		btn.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		btn.setPadding(20, 0, 20, 0);
		btn.setText(text);
		
		return btn;
	}
	
	private LinearLayout createContainer(String key) {
		LinearLayout v = new LinearLayout(getContext());
		v.setTag(key);
		v.setOrientation(LinearLayout.HORIZONTAL);		
		v.setPadding(15, 0, 15, 0);
		MarginLayoutParams params = new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		params.setMargins(15, 0, 15, 0);
		v.setLayoutParams(params);
		v.setGravity(Gravity.CENTER);
		
		return v;
	}
}
