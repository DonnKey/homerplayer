/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018-2019 Donn S. Terry
 * Copyright (c) 2015-2017 Marcin Simonides
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.donnKey.aesopPlayer.service;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import androidx.annotation.NonNull;

import com.donnKey.aesopPlayer.GlobalSettings;
import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.ui.TouchRateJoystick;

import static android.content.Context.SENSOR_SERVICE;
import static com.donnKey.aesopPlayer.AesopPlayerApplication.getAppContext;
import static com.donnKey.aesopPlayer.ui.TouchRateJoystick.RELEASE;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Detect whether the device is being shaken or still, and look for
 * proximity detector activity when face-up-still (as the user is
 * reaching for it).
 */
@Singleton // and in use in only one place at a time
public class DeviceMotionDetector implements SensorEventListener {

    @Inject public GlobalSettings globalSettings;

    public interface Listener {
        void onSignificantMotion();
        void onFaceDownStill();
        @SuppressWarnings("EmptyMethod")
        void onFaceUpStill();
    }

    // These values seem to work pretty well, but further actual practice tuning
    // may be in order.
    private static final float MAX_STILL_TOLERANCE = 1f;
    private static final float MAX_FACEDOWN_DEVIATION = 2f;
    private static final float MIN_SIGNIFICANT_MOTION = 0.5f;
    private static final long MIN_TIME_WINDOW = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long AVG_SMOOTH_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final float[] avgAcceleration = new float[3];

    private long previousTimestamp = 0;
    private final float[] previousValues = new float[3];
    private SamplesQueue queue;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor proximity;
    private final PowerManager powerManager;

    // See note below about the detailed meaning.
    private float midRange;

    private Listener listener;

    // Our single instance
    static private DeviceMotionDetector deviceMotionDetector = null;

    private final ReawakenListener reawakenListener = new ReawakenListener();

    // DetectUserInterest can be called frequently as the device changes state,
    // and not nicely paired with actual awakens.
    // We don't want to add sensor handlers we're not going to delete, so keep
    // track if it's already set up and do nothing.
    private boolean busy;

    // See suspend() below
    private boolean suspended;

    static private boolean enabled = false;

    // Remember the last FACE UP/DOWN we sent and don't repeat. (Send only changes in that.)
    static private MotionType priorType = MotionType.OTHER;

    protected enum MotionType {
        FACE_DOWN,
        FACE_UP,
        ACCELERATING,
        OTHER
    }

    @NonNull
    public static DeviceMotionDetector getDeviceMotionDetector(@NonNull Listener listener) {
        getDeviceMotionDetector();
        deviceMotionDetector.listener = listener;
        return deviceMotionDetector;
    }

    @NonNull
    private static DeviceMotionDetector getDeviceMotionDetector() {
        if (deviceMotionDetector == null) {
            deviceMotionDetector = new DeviceMotionDetector(getAppContext());
            deviceMotionDetector.listener = null;
        }
        return deviceMotionDetector;
    }

    // Singleton private constructor
    private DeviceMotionDetector(Context context) {
        AesopPlayerApplication.getComponent().inject(this);

        // Get the sensor. If it isn't there null things so that callers can simply
        // make the call and not have to think about whether it's there or not.
        sensorManager = (SensorManager) context.getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            accelerometer = null;
            proximity = null;
            powerManager = null;
            return;
        }

        // Ideally TYPE_LINEAR_ACCELERATION but it doesn't always work, so we use the
        // raw one and compensate for g ourselves.
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximity != null) {
            float range = proximity.getMaximumRange();
            midRange = range/2;
        }

        // We'll need this later when we don't have a context.
        powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);

        queue = new SamplesQueue();
    }

    public void enable() {
        proximityDetectorStop();
        busy = false;
        enabled = true;
        // Zero out history... we might have moved a long way while we weren't looking.
        previousTimestamp = 0;
        if (accelerometer == null) {
            return;
        }
        // 4 argument form requires Api19
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    public void disable() {
        enabled = false;
        if (accelerometer == null) {
            return;
        }
        // unregister all listeners just so they don't build up if there's a bug
        sensorManager.unregisterListener(this);
        queue = new SamplesQueue();
        priorType = MotionType.OTHER;
    }

    @SuppressWarnings("unused")
    public boolean hasSensors() {
        return sensorManager != null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            onPSensorChanged(event);
        }
        else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            onMSensorChanged(event);
        }
    }

    private void onMSensorChanged(SensorEvent event) {
        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];

        if (previousTimestamp == 0) {
            // capture and then ignore the first sample so we get at least a little history
            previousTimestamp = event.timestamp;
            for (int i = 0; i < avgAcceleration.length; ++i) {
                previousValues[i] = avgAcceleration[i] = event.values[i];
            }
            return;
        }

        // alpha is (for me) around 0.13, which means that new values don't contribute much each.
        // A smaller smoothing interval would increase the sensitivity at a reduction in accuracy.
        // avgAcceleration[0-2] is the result of a low-pass filter giving us vector g, more or less
        long deltaTimeNano = event.timestamp - previousTimestamp;
        float alpha = ((float) deltaTimeNano) / AVG_SMOOTH_TIME_NANOS;
        float sampleDeltaSum = 0f;
        float accDeltaSum = 0f;
        for (int i = 0; i < avgAcceleration.length; ++i) {
            avgAcceleration[i] = alpha * event.values[i] + (1f - alpha) * avgAcceleration[i];
            final float a = Math.abs(event.values[i]);
            accDeltaSum += Math.abs(a - Math.abs(avgAcceleration[i]));
            sampleDeltaSum += Math.abs(a - Math.abs(previousValues[i]));
            previousValues[i] = event.values[i];
        }

        if (tiltListener != null) {
            checkTiltAngle(x, y, z);
        }

        // accDeltaSum is the sum of the absolute deviations from g (still == 0)
        // sampleDeltaSum is the sum of the changes from the last sample (still == 0)
        // These are almost never exactly zero due to mechanical and electrical jitter

        System.arraycopy(event.values, 0, previousValues, 0, event.values.length);

        final float acceleration = (float) Math.sqrt(x * x + y * y + z * z);
        previousTimestamp = event.timestamp;

        boolean isAccelerating = accDeltaSum > MIN_SIGNIFICANT_MOTION;
        boolean isStill = sampleDeltaSum < MAX_STILL_TOLERANCE;
        isStill &= Math.abs(acceleration - Math.abs(z)) < MAX_FACEDOWN_DEVIATION;

        MotionType sampleType =
                isStill ? (z < 0 ? MotionType.FACE_DOWN : MotionType.FACE_UP)
                : isAccelerating ? MotionType.ACCELERATING
                : MotionType.OTHER;

        // Add this sample, and from that determine the overall motion type if a majority
        // has been accumulated yet.
        queue.add(event.timestamp, sampleType);
        MotionType detectedType = queue.getMotionType();

        // Purge the queue of entries older than the time window
        queue.purgeOld(event.timestamp - MIN_TIME_WINDOW);

        // Purge the queue completely if we resolved a motion type.
        if (detectedType != MotionType.OTHER) {
            queue = new SamplesQueue();
        }

        // events could potentially come in after disable, so  don't pass them on
        if (enabled) {
            switch (detectedType) {
                case FACE_DOWN:
                    if (priorType == MotionType.FACE_DOWN)
                        break;
                    priorType = MotionType.FACE_DOWN;
                    listener.onFaceDownStill();
                    break;
                case FACE_UP:
                    if (priorType == MotionType.FACE_UP)
                        break;
                    priorType = MotionType.FACE_UP;
                    listener.onFaceUpStill();
                    break;
                case ACCELERATING:
                    priorType = MotionType.OTHER;
                    listener.onSignificantMotion();
                    break;
            }
        }
    }

    @SuppressWarnings("EmptyMethod")
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }


    // Inspired by Square's seismic ShakeDetector.
    private static class SamplesQueue {
        private static final int MIN_LENGTH = 4;

        private static class SampleEntry {
            MotionType motionType;
            private long timestamp;

            SampleEntry next = null;
        }

        private SampleEntry newest = null;
        private SampleEntry oldest = null;
        private int count = 0;
        private int acceleratingCount = 0;
        private int faceDownCount = 0;
        private int faceUpCount = 0;

        private SampleEntry unused = null;

        // Insert an entry in the linked list, incrementing the counter for its type
        void add(long timestamp, MotionType type) {
            SampleEntry newSample;
            if (unused != null) {
                newSample = unused;
                unused = newSample.next;
            } else {
                newSample = new SampleEntry();
            }
            newSample.timestamp = timestamp;
            newSample.motionType = type;
            newSample.next = null;

            if (newest != null)
              newest.next = newSample;
            newest = newSample;
            if (oldest == null)
                oldest = newest;
            switch (type) {
                case ACCELERATING:
                    ++acceleratingCount;
                    break;
                case FACE_DOWN:
                    ++faceDownCount;
                    break;
                case FACE_UP:
                    ++faceUpCount;
                    break;
            }
            ++count;
        }

        // Purge old entries (prior to timestamp), retaining a minimum list length
        void purgeOld(long timestamp) {
            while(oldest != null && count > MIN_LENGTH && oldest.timestamp < timestamp) {
                SampleEntry removed = oldest;
                oldest = removed.next;
                remove(removed);
            }
        }

        // For the entries in the list, which one (if any) constitutes 75% of the reports
        // (We're caching counts.)
        MotionType getMotionType() {
            if (newest.timestamp - oldest.timestamp > MIN_TIME_WINDOW) {
                int threshold = (count >> 1) + (count >> 2);  // count * 0.75
                if (faceDownCount >= threshold)
                    return MotionType.FACE_DOWN;
                if (faceUpCount >= threshold)
                    return MotionType.FACE_UP;
                if (acceleratingCount >= threshold)
                    return MotionType.ACCELERATING;
            }

            return MotionType.OTHER;
        }

        // Remove an entry, decrementing the count of its type
        private void remove(SampleEntry sample) {
            --count;
            switch (sample.motionType) {
                case ACCELERATING:
                    --acceleratingCount;
                    break;
                case FACE_DOWN:
                    --faceDownCount;
                    break;
                case FACE_UP:
                    --faceUpCount;
                    break;
            }
            sample.next = unused;
            unused = sample;
        }
    }

    private void onPSensorChanged(SensorEvent event) {
        if (priorType != MotionType.FACE_UP) {
            return;
        }

        // The range of the proximity sensor is messy: it ranges from 0.0 to some per-device
        // maximum. Some devices return only 0.0 and the maximum, others a continuous value.
        // The Android docs only require "some value less than the max" in the binary case,
        // so it could (theoretically) be 7.0 and 6.9999. Whether the range for continuous
        // ranges actually ever includes zero is unknown.
        // At least one device lies about the range in the getRange call, reporting 1.0 when
        // in actuality it reports 0 and 8.0 only. (The device is known to also have
        // an analog proximity detector, although I don't know the details.)
        // This would appear to work in any reasonable 0/nonzero and continuous case,
        // unless it's simply perverse.
        if (event.values[0] < midRange) {
            //near
            reawaken();
        }
    }

    private void proximityDetectorStart() {
        if (proximity == null) {
            return;
        }
        if (!globalSettings.isProximityEnabled()) {
            return;
        }
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void proximityDetectorStop() {
        if (proximity == null) {
            return;
        }
        // unregister all listeners just so they don't build up if there's a bug
        sensorManager.unregisterListener(this);
    }

    private class ReawakenListener implements DeviceMotionDetector.Listener {

        @Override
        public void onSignificantMotion() {
            reawaken();
        }

        @SuppressWarnings("EmptyMethod")
        @Override
        public void onFaceDownStill() {
            /* ignore */
        }

        @SuppressWarnings("EmptyMethod")
        @Override
        public void onFaceUpStill() {
            /* ignore */
        }
    }

    private void motionDetectorStart() {
        this.listener = reawakenListener;
        this.enable();
    }

    private void motionDetectorStop() {
        this.disable();
    }

    private void reawaken() {
        motionDetectorStop();
        proximityDetectorStop();
        busy = false;

        // After a lot of research, I was unable to find a way to achieve what
        // this does without the deprecation warning. There are other ways that
        // might work (setting android:turnScreenOn or the equivalent call), but
        // that requires API 27.
        //noinspection deprecation - SCREEN_BRIGHT_WAKE_LOCK
        PowerManager.WakeLock wl = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                             PowerManager.ACQUIRE_CAUSES_WAKEUP |
                             PowerManager.ON_AFTER_RELEASE,
                "AesopPlayer:reawaken");

        // Screen will stay on 5 seconds if not touched
        // and revert to normal timeouts if touched.
        wl.acquire(5000);
    }

    // Set up a motion detector that runs when the playback and bookList windows get
    // turned off for device sleep. When a motion is detected, wake up the device.
    static public void DetectUserInterest() {
        // Ideally we'd use TYPE_SIGNIFICANT_MOTION for this, but that (on devices where it's
        // even present) is too insensitive. "Significant" means really significant,
        // apparently (as in a full-arm shake).
        DeviceMotionDetector detector = getDeviceMotionDetector();
        if (detector.busy || detector.suspended) {
            return;
        }

        detector.busy = true;
        detector.motionDetectorStart();
        detector.proximityDetectorStart();
    }

    // We don't want to run the DetectUserInterest stuff under certain circumstances that the
    // usual caller can't detect, so let those contexts (specifically, Settings) suspend us.
    // Related: If the device enters locked state, this will continue to be active. It seems
    // reasonable to do so, given the intent, and in practice probably doesn't matter much
    // since the DetectUserInterest feature is really focused on Kiosk mode users anyway.
    static public void suspend() {
        DeviceMotionDetector detector = getDeviceMotionDetector();
        detector.suspended = true;
        detector.motionDetectorStop();
        detector.proximityDetectorStop();
    }

    static public void resume() {
        DeviceMotionDetector detector = getDeviceMotionDetector();
        detector.suspended = false;
        if (detector.busy) {
            detector.motionDetectorStart();
            detector.proximityDetectorStart();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static DeviceMotionDetector getDeviceMotionDetector(TouchRateJoystick.Listener tiltListener) {
        deviceMotionDetector.tiltState = TiltState.UNESTABLISHED;
        deviceMotionDetector.counter = 0;
        deviceMotionDetector.tiltListener = tiltListener;
        return deviceMotionDetector;
    }

    private TouchRateJoystick.Listener tiltListener;
    private final Handler handler = new Handler();
    private static final int INTERVAL = 1000;
    private long startT = 0;
    private long delayNs = 0;
    private int counter;
    private TouchRateJoystick.Direction direction = TouchRateJoystick.Direction.UP; // arbitrary

    // FSM state:
    //   UNESTABLISHED: no direction established
    //   ESTABLISHING: Direction known, no tick yet sent, but one (with counter==0) on next tick.
    //   ESTABLISHED: direction established, sending ticks
    //   CLEARING: direction lost... will send RELEASE on next tick
    //   The objective is to slow the state changes to the clock tick rate to avoid glitchiness
    //   particularly in volume up.
    private enum TiltState {UNESTABLISHED, ESTABLISHING, ESTABLISHED, CLEARING}
    private TiltState tiltState = TiltState.UNESTABLISHED;

    @SuppressWarnings({"DuplicateExpressions", "RedundantSuppression"})
    private void checkTiltAngle(float x, float y, float z) {
        // We rely on the fact that it's not physically possible to change directions
        // without going through the UNESTABLISHED state.
        float p = (float)Math.toDegrees(Math.atan2(x,z));
        float q = (float)Math.toDegrees(Math.atan2(y,z));

        p = Math.round(p);
        q = Math.round(q);

        final int DEFAULT_ANGLE = 40;
        final int TOLERANCE = 10;

        switch (tiltState) {
        case UNESTABLISHED: {
            // Is there enough difference in the angles to say which angle it is?
            if (Math.abs(Math.abs(p) - Math.abs(q)) > DEFAULT_ANGLE / 2.0) {
                if (p >= 0 && Math.abs(p - DEFAULT_ANGLE) < TOLERANCE) {
                    direction = TouchRateJoystick.Direction.UP;
                    tiltState = TiltState.ESTABLISHING;
                }
                else if (p < 0 && Math.abs(-p - DEFAULT_ANGLE) < TOLERANCE) {
                    direction = TouchRateJoystick.Direction.DOWN;
                    tiltState = TiltState.ESTABLISHING;
                }
                else if (q <= 0 && Math.abs(-q - DEFAULT_ANGLE) < TOLERANCE) {
                    direction = TouchRateJoystick.Direction.LEFT;
                    tiltState = TiltState.ESTABLISHING;
                }
                else if (q > 0 && Math.abs(q - DEFAULT_ANGLE) < TOLERANCE) {
                    direction = TouchRateJoystick.Direction.RIGHT;
                    tiltState = TiltState.ESTABLISHING;
                }
            }
            if (tiltState == TiltState.ESTABLISHING) {
                // Wait for 1/2 tick before calling onTouchRate - "just moving around"
                // is thus ignored pretty well.
                // A shorter initial interval the first time seems subjectively better
                delayNs = INTERVAL/2;
                handler.postDelayed(this::onTick, INTERVAL/2);
                counter = 0;
            }
            break;
        }

        case ESTABLISHED:
        case ESTABLISHING: {
            TiltState newTiltState = TiltState.UNESTABLISHED;
            if (Math.abs(Math.abs(p) - Math.abs(q)) <= DEFAULT_ANGLE / 2.0) {
                // Too close to call, release
                newTiltState = TiltState.CLEARING;
            }
            else {
                switch (direction) {
                case UP:
                    if (p < 0 || Math.abs(p - DEFAULT_ANGLE) > 1.5 * TOLERANCE) {
                        newTiltState = TiltState.CLEARING;
                    }
                    break;
                case DOWN:
                    if (p >= 0 || Math.abs(-p - DEFAULT_ANGLE) > 1.5 * TOLERANCE) {
                        newTiltState = TiltState.CLEARING;
                    }
                    break;
                case LEFT:
                    if (q > 0 || Math.abs(-q - DEFAULT_ANGLE) > 1.5 * TOLERANCE) {
                        newTiltState = TiltState.CLEARING;
                    }
                    break;
                case RIGHT:
                    if (q <= 0 || Math.abs(q - DEFAULT_ANGLE) > 1.5 * TOLERANCE) {
                        newTiltState = TiltState.CLEARING;
                    }
                    break;
                }
            }
            if (newTiltState == TiltState.CLEARING) {
                if (tiltState == TiltState.ESTABLISHING) {
                    // We never got to a tick. Just ignore everything.
                    tiltState = TiltState.UNESTABLISHED;
                }
                else {
                    // Clear the state on next tick
                    tiltState = TiltState.CLEARING;
                }
            }
            }
            break;
        case CLEARING:
            // Do nothing, await a tick
            break;
        }
    }

    private void onTick() {
        handler.removeCallbacksAndMessages(null); // in case of several
        switch (tiltState) {
        case UNESTABLISHED: {
            // Happens when we momentarily enter ESTABLISHING but leave before a tick.
            return;
        }
        case ESTABLISHING: {
            tiltState = TiltState.ESTABLISHED;
            break;
        }
        case ESTABLISHED: {
            break;
        }
        case CLEARING: {
            tiltState = TiltState.UNESTABLISHED;
            tiltListener.onTouchRate(direction, RELEASE);
            return;
        }
        }

        handler.postDelayed(this::onTick, INTERVAL);

        final long nanoT = System.nanoTime();
        final long deltaT = nanoT-startT;

        if (deltaT > delayNs) {
            startT = nanoT;
            tiltListener.onTouchRate(direction, counter);
            delayNs = INTERVAL;
            counter++;
        }
    }
}
