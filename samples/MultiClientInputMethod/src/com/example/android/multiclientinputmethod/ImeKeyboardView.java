/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.example.android.multiclientinputmethod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupWindow;

/**
 * {@link KeyboardView} with a long-press accent popup that works like on modern keyboards: keep
 * the finger down, slide onto the accented character and release to pick it. Releasing over the
 * original key picks the original character; releasing anywhere else picks nothing.
 */
public class ImeKeyboardView extends KeyboardView {
    private static final String TAG = "ImeKeyboardView";
    private static final int NONE = -1;

    private PopupWindow mAccentWindow;
    private AccentView mAccentView;
    private int[] mAccentCodes;
    private int mBaseIndex = NONE;
    // Geometry in this view's coordinates.
    private final RectF mKeyRect = new RectF();
    private final RectF mPopupRect = new RectF();
    private float mCellWidth;
    private float mCellHeight;

    public ImeKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ImeKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected boolean onLongPress(Keyboard.Key key) {
        if (key.popupCharacters != null && key.popupCharacters.length() > 0
                && key.codes != null && key.codes.length > 0 && key.codes[0] > 32
                && showAccentPopup(key)) {
            return true;  // KeyboardView then aborts the normal key press.
        }
        return super.onLongPress(key);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private boolean showAccentPopup(Keyboard.Key key) {
        dismissAccentPopup();
        final boolean shifted = isShifted();
        final String variants = key.popupCharacters.toString();
        final int n = 1 + variants.length();

        // Logical order: original character first, then the variants.
        int[] codes = new int[n];
        codes[0] = key.codes[0];
        for (int i = 0; i < variants.length(); i++) {
            codes[i + 1] = variants.charAt(i);
        }

        mCellWidth = Math.max(key.width, dp(44));
        mCellHeight = key.height;
        final float total = mCellWidth * n;
        float left = getPaddingLeft() + key.x;
        boolean leftward = false;
        if (left + total > getWidth()) {
            // Not enough room on the right: grow the popup to the left instead.
            left = getPaddingLeft() + key.x + key.width - total;
            leftward = true;
        }
        if (left < 0) {
            left = 0;
        }
        final float top = getPaddingTop() + key.y - mCellHeight - dp(6);

        if (leftward) {
            final int[] reversed = new int[n];
            for (int i = 0; i < n; i++) {
                reversed[i] = codes[n - 1 - i];
            }
            codes = reversed;
            mBaseIndex = n - 1;
        } else {
            mBaseIndex = 0;
        }
        final String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            final int c = shifted ? Character.toUpperCase(codes[i]) : codes[i];
            labels[i] = String.valueOf((char) c);
        }

        mAccentCodes = codes;
        mKeyRect.set(getPaddingLeft() + key.x, getPaddingTop() + key.y,
                getPaddingLeft() + key.x + key.width, getPaddingTop() + key.y + key.height);
        mPopupRect.set(left, top, left + total, top + mCellHeight);

        mAccentView = new AccentView(getContext(), labels, mCellWidth, mCellHeight);
        mAccentView.setHighlight(mBaseIndex);
        final PopupWindow window = new PopupWindow(mAccentView, (int) total, (int) mCellHeight,
                false);
        window.setBackgroundDrawable(null);
        window.setClippingEnabled(false);
        window.setTouchable(false);   // The touch stream stays with this view.
        window.setAnimationStyle(0);
        final int[] location = new int[2];
        getLocationInWindow(location);
        try {
            window.showAtLocation(this, Gravity.NO_GRAVITY,
                    location[0] + (int) left, location[1] + (int) top);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot show accent popup", e);
            mAccentView = null;
            mAccentCodes = null;
            return false;
        }
        mAccentWindow = window;
        return true;
    }

    /** Index of the popup cell under the finger, or NONE. */
    private int hit(float x, float y) {
        if (mAccentWindow == null) {
            return NONE;
        }
        if (mKeyRect.contains(x, y)) {
            return mBaseIndex;  // Still over the original key.
        }
        if (x >= mPopupRect.left && x < mPopupRect.right
                && y >= mPopupRect.top - mCellHeight / 2 && y < mKeyRect.top) {
            final int index = (int) ((x - mPopupRect.left) / mCellWidth);
            return Math.max(0, Math.min(mAccentCodes.length - 1, index));
        }
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        if (mAccentWindow == null) {
            return super.onTouchEvent(me);
        }
        switch (me.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mAccentView.setHighlight(hit(me.getX(), me.getY()));
                return true;
            case MotionEvent.ACTION_UP: {
                final int index = hit(me.getX(), me.getY());
                final int code = index == NONE ? NONE : mAccentCodes[index];
                dismissAccentPopup();
                if (code != NONE) {
                    final OnKeyboardActionListener listener = getOnKeyboardActionListener();
                    if (listener != null) {
                        listener.onKey(code, new int[] {code});
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dismissAccentPopup();
                return true;
            default:
                return true;  // Ignore extra pointers while the popup is open.
        }
    }

    private void dismissAccentPopup() {
        if (mAccentWindow != null) {
            try {
                mAccentWindow.dismiss();
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot dismiss accent popup", e);
            }
        }
        mAccentWindow = null;
        mAccentView = null;
        mAccentCodes = null;
        mBaseIndex = NONE;
    }

    @Override
    public void closing() {
        dismissAccentPopup();
        super.closing();
    }

    @Override
    public void onDetachedFromWindow() {
        dismissAccentPopup();
        super.onDetachedFromWindow();
    }

    /** Draws the popup row with the same colors as the keyboard. */
    private static final class AccentView extends View {
        private final String[] mLabels;
        private final float mCellW;
        private final float mCellH;
        private final Paint mPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final float mRadius;
        private final float mInset;
        private int mHighlight = NONE;

        AccentView(Context context, String[] labels, float cellW, float cellH) {
            super(context);
            mLabels = labels;
            mCellW = cellW;
            mCellH = cellH;
            final float density = context.getResources().getDisplayMetrics().density;
            mRadius = 10 * density;
            mInset = 3 * density;
            mPanel.setColor(context.getColor(R.color.keyboard_background));
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(density);
            mStroke.setColor(context.getColor(R.color.key_shadow));
            mHighlightPaint.setColor(context.getColor(R.color.key_checked));
            mText.setColor(context.getColor(R.color.key_text));
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22,
                    context.getResources().getDisplayMetrics()));
        }

        void setHighlight(int index) {
            if (index != mHighlight) {
                mHighlight = index;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension((int) (mCellW * mLabels.length), (int) mCellH);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mRect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPanel);
            mRect.inset(mStroke.getStrokeWidth() / 2, mStroke.getStrokeWidth() / 2);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mStroke);
            final Paint.FontMetrics fm = mText.getFontMetrics();
            final float baseline = mCellH / 2 - (fm.ascent + fm.descent) / 2;
            for (int i = 0; i < mLabels.length; i++) {
                if (i == mHighlight) {
                    mRect.set(i * mCellW + mInset, mInset, (i + 1) * mCellW - mInset,
                            mCellH - mInset);
                    canvas.drawRoundRect(mRect, mRadius - 2, mRadius - 2, mHighlightPaint);
                }
                canvas.drawText(mLabels[i], i * mCellW + mCellW / 2, baseline, mText);
            }
        }
    }
}
