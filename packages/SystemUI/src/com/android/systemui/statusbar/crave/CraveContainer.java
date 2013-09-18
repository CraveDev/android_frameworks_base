package com.android.systemui.statusbar.crave;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

public class CraveContainer
{
	private int mPosition;
	private int mTextColor;
	private int mMarginLeft;
	private int mMarginRight;
	private int mTextSize;
	private int mVisibility;
	private boolean mEnabled;

	public CraveContainer()
	{
		this.mPosition = -1; // Left
		this.mTextColor = Color.rgb(102, 102, 102);
		this.mMarginLeft = (this.mPosition != 1) ? 15 : 0;
		this.mMarginRight = (this.mPosition == 1) ? 15 : 0;
		this.mTextSize = 22;
		this.mVisibility = View.VISIBLE;
		this.mEnabled = true;
	}

	CraveContainer(Bundle in)
	{
		this.mPosition = in.getInt("position", -1);
		int color = in.getInt("color", Color.rgb(102, 102, 102));
		this.mTextColor = Color.rgb(((color >> 16) & 0xFF), ((color >> 8) & 0xFF), (color & 0xFF));
		this.mMarginLeft = in.getInt("marginleft", 15);
		this.mMarginRight = in.getInt("marginright", 0);
		this.mTextSize = in.getInt("textsize", 22);
		this.mVisibility = in.getInt("visibility", View.VISIBLE);
		this.mEnabled = in.getBoolean("enabled", true);
	}
	
	public int getPosition() 
	{
		return this.mPosition;
	}

	public int getTextColor()
	{
		return this.mTextColor;
	}

	public int getMarginLeft()
	{
		return this.mMarginLeft;
	}

	public int getMarginRight()
	{
		return this.mMarginRight;
	}

	public int getTextSize()
	{
		return this.mTextSize;
	}

	public int getVisibility()
	{
		return this.mVisibility;
	}

	public boolean getEnabled()
	{
		return this.mEnabled;
	}
	
	@Override
	public String toString()
	{
		return "(mPosition=" + this.mPosition + 
				", mTextColor=" + this.mTextColor +
				", mTextSize=" + this.mTextSize +
				", mMarginLeft=" + this.mMarginLeft +
				", mMarginRight=" + this.mMarginRight +
				", mVisibility=" + this.mVisibility +
				", mEnabled=" + this.mEnabled + ")";
	}
}