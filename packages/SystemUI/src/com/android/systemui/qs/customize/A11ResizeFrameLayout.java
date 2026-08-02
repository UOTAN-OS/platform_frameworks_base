/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.systemui.qs.customize;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.res.R;

/**
 * Tile editor frame that gives the A11 resize handle a reliable gesture target.
 *
 * <p>The tile itself owns the full frame and may create a hardware layer while RecyclerView is
 * animating. Intercepting the handle corner here prevents that layer and ItemTouchHelper from
 * stealing a resize gesture.
 */
public class A11ResizeFrameLayout extends FrameLayout {
    private OnTouchListener mResizeTouchListener;
    private boolean mResizeGesture;
    private final int mHandleTouchSize;
    private final float mDensity;
    private final Paint mBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public A11ResizeFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHandleTouchSize = getResources().getDimensionPixelSize(
                R.dimen.a11_qs_resize_handle_touch_size);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedValue accent = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        mBadgePaint.setColor(accent.resourceId != 0
                ? context.getColor(accent.resourceId) : accent.data);
        mGlyphPaint.setColor(Color.WHITE);
        mGlyphPaint.setStrokeWidth(2f * mDensity);
        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setResizeTouchListener(OnTouchListener listener) {
        mResizeTouchListener = listener;
        if (listener == null) {
            mResizeGesture = false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mResizeGesture = mResizeTouchListener != null
                    && event.getX() >= getWidth() - mHandleTouchSize
                    && event.getY() >= getHeight() - mHandleTouchSize;
            if (mResizeGesture) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return mResizeGesture || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mResizeGesture || mResizeTouchListener == null) {
            return super.onTouchEvent(event);
        }
        final boolean handled = mResizeTouchListener.onTouch(this, event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mResizeGesture = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (mResizeTouchListener == null) {
            return;
        }
        final float radius = 12f * mDensity;
        final float centerX = getWidth() - radius;
        final float centerY = getHeight() - radius;
        canvas.drawCircle(centerX, centerY, radius, mBadgePaint);
        canvas.drawLine(centerX - 5f * mDensity, centerY + 5f * mDensity,
                centerX + 5f * mDensity, centerY - 5f * mDensity, mGlyphPaint);
        canvas.drawLine(centerX, centerY + 5f * mDensity,
                centerX + 5f * mDensity, centerY, mGlyphPaint);
    }
}
