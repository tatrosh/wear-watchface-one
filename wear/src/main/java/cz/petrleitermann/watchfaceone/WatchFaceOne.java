/*
 * Copyright (C) 2014 The Android Open Source Project
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

package cz.petrleitermann.watchfaceone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.Gravity;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceOne extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFaceOne.Engine> mWeakReference;

        public EngineHandler(WatchFaceOne.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceOne.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundScaledBitmap;
        Paint mSideTickPaint;
        Paint mMainTickPaint;
        Paint mHourHandPaint;
        Paint mMinHandPaint;
        Paint mSecHandPaint;
        Calendar mCalendar;

        boolean mAmbient;
        Time mTime;

        //        final Handler mUpdateTimeHandler = new EngineHandler(this);

        // handler to update the time once a second in interactive mode
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler
                                    .sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

//        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
//            @Override
//            public void onReceive(Context context, Intent intent) {
//                mTime.clear(intent.getStringExtra("time-zone"));
//                mTime.setToNow();
//            }
//        };

        // receiver to update the time zone
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceOne.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setStatusBarGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = WatchFaceOne.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            Drawable backgroundDrawable = resources.getDrawable(R.drawable.wfo_numthree, null);
            mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();

            mMainTickPaint = new Paint();
            mMainTickPaint.setColor(resources.getColor(R.color.main_ticks));
            mMainTickPaint.setStrokeWidth(resources.getDimension(R.dimen.tick_stroke));
            mMainTickPaint.setAntiAlias(true);
            mMainTickPaint.setStrokeCap(Paint.Cap.BUTT);
            mMainTickPaint.setTextSize(22);
//            mMainTickPaint.setTextSkewX((float) -0.25);
            mMainTickPaint.setTextAlign(Paint.Align.CENTER);
            mMainTickPaint.setFakeBoldText(true);
//            mMainTickPaint.setFontFeatureSettings();

            mSideTickPaint = new Paint(mMainTickPaint);
            mSideTickPaint.setColor(resources.getColor(R.color.side_ticks));

            mMinHandPaint = new Paint();
            mMinHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mMinHandPaint.setStrokeWidth(resources.getDimension(R.dimen.min_hand_stroke));
            mMinHandPaint.setAntiAlias(true);
            mMinHandPaint.setStrokeCap(Paint.Cap.BUTT);

            mHourHandPaint = new Paint(mMinHandPaint);
            mHourHandPaint.setStrokeWidth(resources.getDimension(R.dimen.hour_hand_stroke));
            mHourHandPaint.setStrokeCap(Paint.Cap.BUTT);

            mSecHandPaint = new Paint();
            mSecHandPaint.setColor(resources.getColor(R.color.sec_hand));
            mSecHandPaint.setStrokeWidth(resources.getDimension(R.dimen.sec_hand_stroke));
            mSecHandPaint.setAntiAlias(true);
            mSecHandPaint.setStrokeCap(Paint.Cap.BUTT);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            mCalendar = Calendar.getInstance();

            mTime = new Time();
        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mMinHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            // Update the time
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
//                canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, null);
                canvas.drawColor(Color.BLACK);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            // Constant to help calculate clock hand rotations
            final float TWO_PI = (float) Math.PI * 2f;

            double sinVal = 0, cosVal = 0, angle = 0;
            float length1 = 0, length2 = 0;
            float x1 = 0, y1 = 0, x2 = 0, y2 = 0;

            // draw ticks on ambient display
            // works good on not ambient display as well, only need to draw numbers maybe?
//            if (mAmbient) {
                // main ticks
                length1 = centerX - 7;
                length2 = centerX + 70;
                for (int i = 0; i < 60; i++) {
                    angle = (i * Math.PI * 2 / 60);
                    sinVal = Math.sin(angle);
                    cosVal = Math.cos(angle);
                    x1 = (float)(sinVal * length1);
                    y1 = (float)(-cosVal * length1);
                    x2 = (float)(sinVal * length2);
                    y2 = (float)(-cosVal * length2);

                    if (i % 15 == 0) {
                    canvas.drawLine(centerX + x1, centerY + y1, centerX + x2,
                            centerY + y2, mMainTickPaint);
                    }
                }

                // side ticks
                length1 = centerX + 19;
                length2 = centerX + 30;
                for (int i = 0; i < 60; i++) {
                    angle = (i * Math.PI * 2 / 60);
                    sinVal = Math.sin(angle);
                    cosVal = Math.cos(angle);
                    x1 = (float)(sinVal * length1);
                    y1 = (float)(-cosVal * length1);
                    x2 = (float)(sinVal * length2);
                    y2 = (float)(-cosVal * length2);

                    if (i % 5 == 0 && i % 15 != 0) {
                        canvas.drawLine(centerX + x1, centerY + y1, centerX + x2,
                                centerY + y2, mSideTickPaint);
                    }
                }
//            }

            // draw numbers
            canvas.drawText("12", centerX, centerY - 132, mMainTickPaint);
            canvas.drawText("6", centerX, centerY + 145, mMainTickPaint);

            canvas.drawText("3", centerX + 142, centerY + 8, mMainTickPaint);
            canvas.drawText("9", centerX - 142, centerY + 8, mMainTickPaint);

            // Compute rotations and lengths for the clock hands.
            float seconds = mCalendar.get(Calendar.SECOND) +
                    mCalendar.get(Calendar.MILLISECOND) / 1000f;
            float secRot = seconds / 60f * TWO_PI;
            float minutes = mCalendar.get(Calendar.MINUTE) + seconds / 60f;
            float minRot = minutes / 60f * TWO_PI;
            float hours = mCalendar.get(Calendar.HOUR) + minutes / 60f;
            float hrRot = hours / 12f * TWO_PI;

            float secLength = centerX + 30;
            float minLength = centerX - 25;
            float hrLength = centerX - 50;

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mMinHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHourHandPaint);


            // hands center circles
            canvas.drawCircle(centerX, centerY, 8, mHourHandPaint);

            if (!mAmbient) {
                canvas.drawCircle(centerX, centerY, 5, mSecHandPaint);
            }
            else {
                canvas.drawCircle(centerX, centerY, 5, mBackgroundPaint);
            }

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mSecHandPaint);
            }

            canvas.drawCircle(centerX, centerY, 2, mHourHandPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceOne.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceOne.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
