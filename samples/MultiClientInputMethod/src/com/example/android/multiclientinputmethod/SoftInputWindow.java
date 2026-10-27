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

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.inputmethodservice.MultiClientInputMethodServiceDelegate;
import android.os.IBinder;
import android.os.LocaleList;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;

import java.util.Arrays;

final class SoftInputWindow extends Dialog {
    private static final String TAG = "SoftInputWindow";
    private static final boolean DEBUG = true;

    private KeyboardView mKeyboardView;

    private Keyboard mQwertygKeyboard;
    private Keyboard mSymbolKeyboard;
    private Keyboard mSymbolShiftKeyboard;

    // Kept so that the UI can be rebuilt (e.g. light <-> dark switch) without losing the
    // connection to the current IME client.
    private KeyboardView.OnKeyboardActionListener mActionListener;

    private int mClientId = MultiClientInputMethodServiceDelegate.INVALID_CLIENT_ID;
    private int mTargetWindowHandle = MultiClientInputMethodServiceDelegate.INVALID_WINDOW_HANDLE;

    private static final KeyboardView.OnKeyboardActionListener sNoopListener =
            new NoopKeyboardActionListener();

    SoftInputWindow(Context context, IBinder token) {
        super(context, android.R.style.Theme_DeviceDefault_InputMethod);

        final LayoutParams lp = getWindow().getAttributes();
        lp.type = LayoutParams.TYPE_INPUT_METHOD;
        lp.setTitle("InputMethod");
        lp.gravity = Gravity.BOTTOM;
        lp.width = LayoutParams.MATCH_PARENT;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.token = token;
        getWindow().setAttributes(lp);

        final int windowSetFlags = LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        final int windowModFlags = LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_DIM_BEHIND
                | LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        getWindow().setFlags(windowSetFlags, windowModFlags);

        mActionListener = sNoopListener;
        buildUi();
    }

    /**
     * (Re)creates the keyboard views from the current resources, so that colors and icons follow
     * the current light/dark mode. Keeps the selected keyboard page, the shift state and the
     * active action listener.
     */
    private void buildUi() {
        final Context context = getContext();

        final Keyboard previous = mKeyboardView != null ? mKeyboardView.getKeyboard() : null;
        final boolean wasShifted = mKeyboardView != null && mKeyboardView.isShifted();
        final boolean wasSymbol = previous != null && previous == mSymbolKeyboard;
        final boolean wasSymbolShift = previous != null && previous == mSymbolShiftKeyboard;

        final float density = context.getResources().getDisplayMetrics().density;
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(context.getColor(R.color.keyboard_background));
        // The theme's window background can be translucent; make everything behind the keys
        // opaque (window, container and the KeyboardView itself, see layout/input.xml).
        getWindow().setBackgroundDrawable(
                new ColorDrawable(context.getColor(R.color.keyboard_background)));
        layout.setPadding((int) (4 * density), (int) (6 * density),
                (int) (4 * density), (int) (8 * density));

        mKeyboardView = (KeyboardView) getLayoutInflater().inflate(R.layout.input, null);
        mQwertygKeyboard = new Keyboard(context, pickLetterLayout(context));
        mSymbolKeyboard = new Keyboard(context, R.xml.symbols);
        mSymbolShiftKeyboard = new Keyboard(context, R.xml.symbols_shift);
        mKeyboardView.setKeyboard(wasSymbolShift ? mSymbolShiftKeyboard
                : (wasSymbol ? mSymbolKeyboard : mQwertygKeyboard));
        mKeyboardView.setShifted(wasShifted);
        mKeyboardView.setOnKeyboardActionListener(mActionListener);

        // TODO(b/158663354): Disabling keyboard popped preview key since it is currently broken.
        mKeyboardView.setPreviewEnabled(false);

        layout.addView(mKeyboardView);

        setContentView(layout, new ViewGroup.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // TODO: Check why we need to call this.
        getWindow().setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    /**
     * Picks the letter layout that matches the system language. The first language in the
     * system locale list for which a {@code res/xml/kbd_<language>.xml} exists wins, so adding
     * a language only needs a new kbd_xx.xml file. Falls back to English.
     */
    private static int pickLetterLayout(Context context) {
        final LocaleList locales = context.getResources().getConfiguration().getLocales();
        for (int i = 0; i < locales.size(); i++) {
            final int id = context.getResources().getIdentifier(
                    "kbd_" + locales.get(i).getLanguage(), "xml", context.getPackageName());
            if (id != 0) {
                return id;
            }
        }
        return R.xml.kbd_en;
    }

    @Override
    public void hide() {
        if (mKeyboardView != null) {
            mKeyboardView.closing();  // Also dismisses an open accent popup.
        }
        super.hide();
    }

    /** Called when the system configuration (e.g. night mode or language) changed. */
    void onThemeChanged() {
        buildUi();
    }

    private void setActionListener(KeyboardView.OnKeyboardActionListener listener) {
        mActionListener = listener;
        mKeyboardView.setOnKeyboardActionListener(listener);
    }

    int getClientId() {
        return mClientId;
    }

    int getTargetWindowHandle() {
        return mTargetWindowHandle;
    }

    boolean isQwertyKeyboard() {
        return mKeyboardView.getKeyboard() == mQwertygKeyboard;
    }

    boolean isSymbolKeyboard() {
        Keyboard keyboard = mKeyboardView.getKeyboard();
        return keyboard == mSymbolKeyboard || keyboard == mSymbolShiftKeyboard;
    }

    void onFinishClient() {
        setActionListener(sNoopListener);
        mClientId = MultiClientInputMethodServiceDelegate.INVALID_CLIENT_ID;
        mTargetWindowHandle = MultiClientInputMethodServiceDelegate.INVALID_WINDOW_HANDLE;
    }

    void onDummyStartInput(int clientId, int targetWindowHandle) {
        if (DEBUG) {
            Log.v(TAG, "onDummyStartInput clientId=" + clientId
                    + " targetWindowHandle=" + targetWindowHandle);
        }
        setActionListener(sNoopListener);
        mClientId = clientId;
        mTargetWindowHandle = targetWindowHandle;
    }

    void onStartInput(int clientId, int targetWindowHandle, InputConnection inputConnection) {
        if (DEBUG) {
            Log.v(TAG, "onStartInput clientId=" + clientId
                    + " targetWindowHandle=" + targetWindowHandle);
        }
        mClientId = clientId;
        mTargetWindowHandle = targetWindowHandle;
        Log.i(TAG, "onStartInput BIND ic=" + Integer.toHexString(System.identityHashCode(
                inputConnection)));
        setActionListener(new NoopKeyboardActionListener() {
            @Override
            public void onKey(int primaryCode, int[] keyCodes) {
                if (DEBUG) {
                    Log.v(TAG, "onKey clientId=" + clientId + " primaryCode=" + primaryCode
                            + " keyCodes=" + Arrays.toString(keyCodes));
                }
                boolean isShifted = isShifted();  // Store the current state before resetting it.
                resetShift();
                switch (primaryCode) {
                    case Keyboard.KEYCODE_CANCEL:
                        hide();
                        break;
                    case Keyboard.KEYCODE_DELETE:
                        inputConnection.sendKeyEvent(
                                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        inputConnection.sendKeyEvent(
                                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                        break;
                    case Keyboard.KEYCODE_MODE_CHANGE:
                        handleSwitchKeyboard();
                        break;
                    case Keyboard.KEYCODE_SHIFT:
                        handleShift(isShifted);
                        break;
                    default:
                        handleCharacter(inputConnection, primaryCode, isShifted);
                        break;
                }
            }

            @Override
            public void onText(CharSequence text) {
                if (DEBUG) {
                    Log.v(TAG, "onText clientId=" + clientId + " text=" + text);
                }
                if (inputConnection == null) {
                    return;
                }
                inputConnection.commitText(text, 0);
            }
        });
    }

    void handleSwitchKeyboard() {
        if (isQwertyKeyboard()) {
            mKeyboardView.setKeyboard(mSymbolKeyboard);
        } else {
            mKeyboardView.setKeyboard(mQwertygKeyboard);
        }

    }

    boolean isShifted() {
        return mKeyboardView.isShifted();
    }

    void resetShift() {
        if (isSymbolKeyboard() && isShifted()) {
            mKeyboardView.setKeyboard(mSymbolKeyboard);
        }
        mKeyboardView.setShifted(false);
    }

    void handleShift(boolean isShifted) {
        if (isSymbolKeyboard()) {
            mKeyboardView.setKeyboard(isShifted ? mSymbolKeyboard : mSymbolShiftKeyboard);
        }
        mKeyboardView.setShifted(!isShifted);
    }

    void handleCharacter(InputConnection inputConnection, int primaryCode, boolean isShifted) {
        if (isQwertyKeyboard() && isShifted) {
            primaryCode = Character.toUpperCase(primaryCode);
        }
        final boolean ok = inputConnection.commitText(String.valueOf((char) primaryCode), 1);
        Log.i(TAG, "commitText ic=" + Integer.toHexString(System.identityHashCode(inputConnection))
                + " result=" + ok);
    }
}
