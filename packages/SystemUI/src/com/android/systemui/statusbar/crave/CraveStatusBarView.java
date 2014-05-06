package com.android.systemui.statusbar.crave;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar.NavigationBarCallback;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.NavigationButtons;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.KeyButtonView;

public class CraveStatusBarView extends FrameLayout implements NavigationBarCallback, View.OnClickListener {
	private static final String TAG = "CraveStatusBarView";
	private static final boolean DEBUG = BaseStatusBar.DEBUG;
	
	private static final String HOME_STRING = "sys_home";
    private static final String BACK_STRING = "sys_back";
    private static final String CLOCK_STRING = "sys_clock";
    private static final String BATTERY_STRING = "sys_battery";
    private static final String MANAGEMENT_STRING = "sys_management";
    
    private static final int TYPE_ICON = 1;
    private static final int TYPE_BUTTON = 2;
    private static final int TYPE_TEXT = 3;
    
    private static final int POSITION_LEFT = -1;
    private static final int POSITION_CENTER = 0;
    private static final int POSITION_RIGHT = 1;
	
	public static Typeface TypefaceRegular;
	public static Typeface TypefaceMedium;
	public static Typeface TypefaceDigital;
	
	HashMap<String, ComponentContainer> mComponentMap = new HashMap<String, ComponentContainer>();
	
	LinearLayout mLeftContainer;
	LinearLayout mCenterContainer;
	LinearLayout mRightContainer;
	
	BatteryController mBatteryController;
	
	class ComponentContainer {
		View view;
		View subView;
		int type;
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
		mCenterContainer = (LinearLayout)findViewById(R.id.centerArea);
		mRightContainer = (LinearLayout)findViewById(R.id.rightArea);
		
		loadDefaultComponents();
	}
	
	private void loadDefaultComponents() {
		// Back button
		KeyButtonView btnBack = (KeyButtonView)findViewById(R.id.one);
		btnBack.setInfo(NavigationButtons.BACK, false, false);
		ComponentContainer container = new ComponentContainer(btnBack, -1);
		container.isCustom = false;
		mComponentMap.put(BACK_STRING, container);
		
		// Home button
		KeyButtonView btnHome = (KeyButtonView)findViewById(R.id.two);
		btnHome.setInfo(NavigationButtons.HOME, false, false);
		btnHome.setSupportLongPress(false);
		container = new ComponentContainer(btnHome, -1);
		container.isCustom = false;
		mComponentMap.put(HOME_STRING, container); 
		
		// Management button
		ImageView sysManagementButton = (ImageView)findViewById(R.id.managementButton);
		sysManagementButton.setOnClickListener(this);
		container = new ComponentContainer(sysManagementButton, POSITION_CENTER);
		container.isCustom = false;
		mComponentMap.put(MANAGEMENT_STRING, container);
		
		// Crave Clock
		CraveClock sysClock = (CraveClock)findViewById(R.id.craveClock); 
		sysClock.setTypeface(TypefaceDigital);
		sysClock.setTextSize(22);
		sysClock.setTextColor(Color.rgb(102, 102, 102));
		container = new ComponentContainer(sysClock, POSITION_RIGHT);
		container.isCustom = false;
		mComponentMap.put(CLOCK_STRING, container); 
		
		// Crave battery
		mBatteryController = new BatteryController(mContext);
		CraveBattery sysBattery = (CraveBattery)findViewById(R.id.craveBattery);
        mBatteryController.addStateChangedCallback(sysBattery);
        container = new ComponentContainer(sysBattery, POSITION_RIGHT);
		container.isCustom = false;
		mComponentMap.put(BATTERY_STRING, container); 
        
	}
	
	public void updateDefaultComponents()
	{
		CraveClock sysClock = (CraveClock)mComponentMap.get(CLOCK_STRING).view;
		sysClock.updateSettings();
	}

	@Override
	public void setNavigationIconHints(int hints) {
		// Empty
	}

	@Override
	public void setMenuVisibility(boolean showMenu) {
		// Empty
	}

	@Override
	public void setDisabledFlags(int disabledFlags) {
		// Empty
	}

	@Override
	public void onClick(View v) {
		Slog.i(TAG, "onClick - id=" + v.getId() + ", tag=" + v.getTag());
		if (v.getId() == R.id.managementButton) {
			getContext().sendBroadcast(new Intent(Intent.CRAVEOS_NAVBAR_ACTION_MANAGEMENT_BUTTON));
		} else {
			if (v.getTag() != null) {
				String key = (String)v.getTag();
				
				Slog.v(TAG, "onClick - Key: " + key);
				
				ComponentContainer container = mComponentMap.get(key);
				if (container.action.length() > 0) {
					mContext.sendBroadcast(new Intent(container.action));
				}
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
	
	public void toggleComponentEnable(String key, boolean isEnabled) {
		if (mComponentMap.containsKey(key)) {
			ComponentContainer component = mComponentMap.get(key);
			
			if (component.subView != null) {
				component.subView.setEnabled(isEnabled);
			} else if (component.view != null) {
				component.view.setEnabled(isEnabled);
			}
		}
	}
	
	public void clearCustomComponents() {
		Set<Entry<String, ComponentContainer>> components = new HashSet<Entry<String,ComponentContainer>>(mComponentMap.entrySet());
		for(Entry<String, ComponentContainer> entry : components) {
			ComponentContainer component = entry.getValue();
			if (component.isCustom) {
				mComponentMap.remove(entry.getKey());
				
				if (component.position == POSITION_LEFT) {
					mLeftContainer.removeView(component.view);
				} else if (component.position == POSITION_RIGHT) {
					mRightContainer.removeView(component.view);
				} else {
					mCenterContainer.removeView(component.view);
				}
			}
		}
	}
	
	public void resetNavigationBar() {
		Set<Entry<String, ComponentContainer>> components = new HashSet<Entry<String,ComponentContainer>>(mComponentMap.entrySet());
		for(Entry<String, ComponentContainer> entry : components) {
			ComponentContainer component = entry.getValue();
			if (component.isCustom) {
				mComponentMap.remove(entry.getKey());
				
				if (component.position == POSITION_LEFT) {
					mLeftContainer.removeView(component.view);
				} else if (component.position == POSITION_RIGHT) {
					mRightContainer.removeView(component.view);
				} else {
					mCenterContainer.removeView(component.view);
				}
			} else {
				if (component.view.getVisibility() != View.VISIBLE) {
					component.view.setVisibility(View.VISIBLE);
				}
			}
		}
	}
	
	public void addComponent(String key, int type, String text, byte[] icon, String action, CraveContainer craveContainer) {
		addComponent(key, type, text, icon, action, craveContainer, true);
	}
	
	private void addComponent(String key, int type, String text, byte[] icon, String action, CraveContainer craveContainer, boolean isCustom) {
		int position = craveContainer.getPosition();
		if (position < POSITION_LEFT || position > POSITION_RIGHT) {
			Slog.w(TAG, "Unknown position (" + position + ").");
			return;
		}
		
		if (mComponentMap.containsKey(key)) {
			Slog.w(TAG, "View with key (" + key + ") already exists.");
			return;
		}
		
		LinearLayout view = createContainer(craveContainer.getMarginLeft(), craveContainer.getMarginRight(), position);
		View subView = null;
		if (type == TYPE_ICON) {
			subView = createIcon(icon);
		} else if (type == TYPE_BUTTON) {			
			subView = createButton(text);
		} else if (type == TYPE_TEXT) {
			subView = createTextView(text, craveContainer.getTextColor(), craveContainer.getTextSize());
		} 
		
		if (subView == null) {
			Slog.w(TAG, "Unknown type ("+type+") for key " + key);
			return;
		}
		
		subView.setTag(key);
		subView.setEnabled(craveContainer.getEnabled());
		view.addView(subView);
		
		Slog.i(TAG, "Added new component (key=" + key + 
				", type=" + type +
				", text=" + text + 
				", icon=" + ((icon == null) ? "null" : "available") + 
				", action=" + action +
				", position=" + position + 
				", craveContainer=" + craveContainer.toString() + ")");
				
		ComponentContainer container = new ComponentContainer(view, position);
		container.subView = subView;
		container.isCustom = isCustom;
		container.type = type;
		container.view.setVisibility(craveContainer.getVisibility());
		
		// Add OnClickListener to subview
		if (action.length() > 0) {
			container.subView.setOnClickListener(this);
			container.action = action;
		}
		
		if (position == POSITION_CENTER)
			mCenterContainer.addView(container.view);
		else if (position == POSITION_RIGHT) {
			mRightContainer.addView(container.view, 0);
		} else {
			mLeftContainer.addView(container.view);
		}
		
		mComponentMap.put(key, container);
	}
	
	public void updateComponent(String key, String text, byte[] icon) {		
		if (!mComponentMap.containsKey(key)) {
			Slog.w(TAG, "View with key (" + key + ") does not exists.");
			return;
		}
		
		ComponentContainer container = mComponentMap.get(key);
		LinearLayout layout = (LinearLayout)container.view;
		
		if (container.type == TYPE_ICON && icon != null) {
			layout.removeAllViews();
			layout.addView(createIcon(icon));
		} else if (container.type == TYPE_BUTTON && text.length() > 0) {
			((Button)container.subView).setText(text);
		} else if (container.type == TYPE_TEXT && text.length() > 0) {
			((TextView)container.subView).setText(text);
		}
	}
	
	public void removeComponent(String key) {
		if (mComponentMap.containsKey(key) && mComponentMap.get(key).isCustom) {
			if (DEBUG)
				Slog.d(TAG, "Removing component with key: " + key);
			
			ComponentContainer container = mComponentMap.remove(key);
			if (container != null) {
				if (container.position == POSITION_LEFT)
					mLeftContainer.removeView(container.view);
				else if (container.position == POSITION_RIGHT)
					mRightContainer.removeView(container.view);
				else
					mCenterContainer.removeView(container.view);
			}
		} else {
			Slog.v(TAG, "Component with key " + key + " doesn't exists or is not a custom component.");
		}
	}
	
	private ImageView createIcon(byte[] icon) {		
		Bitmap bmp = BitmapFactory.decodeByteArray(icon, 0, icon.length);
		ImageView img = new ImageView(getContext());
		img.setImageBitmap(bmp);
		img.setScaleType(ScaleType.CENTER_INSIDE);
		img.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
		
		return img;
	}
	
	private Button createButton(String text) {
		Button btn = new Button(getContext());
		btn.setTextColor(mContext.getResources().getColorStateList(R.color.button_text));
		btn.setTypeface(TypefaceMedium);
		btn.setTextSize(22);
		btn.setBackgroundResource(R.drawable.crave_nav_button);
		btn.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		btn.setPadding(15, 0, 15, 0);
		btn.setText(text);
		
		return btn;
	}
	
	private TextView createTextView(String text, int color, int size) {
		TextView tv = new TextView(getContext());
		tv.setTypeface(TypefaceMedium);
		tv.setTextColor(color);
		tv.setTextSize(size);
		tv.setText(text);
		
		return tv;
	}
	
	private LinearLayout createContainer(int paddingLeft, int paddingRight, int position) {
		LinearLayout v = new LinearLayout(getContext());
		v.setOrientation(LinearLayout.HORIZONTAL);		
		v.setPadding(paddingLeft, 0, paddingRight, 0);
		
		MarginLayoutParams params = new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
		params.setMargins(paddingLeft, 0, paddingRight, 0);
		v.setLayoutParams(params);
		
		if (position == POSITION_LEFT || position == POSITION_CENTER) {
			v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
		} else {
			v.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
		}
		
		return v;
	}
}
