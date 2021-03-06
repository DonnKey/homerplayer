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
package com.donnKey.aesopPlayer.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import androidx.annotation.NonNull;

import com.donnKey.aesopPlayer.ApplicationScope;
import com.donnKey.aesopPlayer.events.BatteryStatusChangeEvent;

import javax.inject.Inject;

import org.greenrobot.eventbus.EventBus;

public class BatteryStatusProvider extends BroadcastReceiver {

    private final IntentFilter batteryStatusIntentFilter;
    private final Context applicationContext;
    private final EventBus eventBus;

    @Inject
    public BatteryStatusProvider(@ApplicationScope Context applicationContext, EventBus eventBus) {
        this.applicationContext = applicationContext;
        this.eventBus = eventBus;
        batteryStatusIntentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    }

    public void start() {
        Intent batteryStatusIntent = applicationContext.registerReceiver(this, batteryStatusIntentFilter);
        if (batteryStatusIntent != null)
            notifyBatteryStatus(getBatteryStatus(batteryStatusIntent));
    }

    public void stop() {
        applicationContext.unregisterReceiver(this);
    }


    @Override
    public void onReceive(Context context, @NonNull Intent intent) {
        if (batteryStatusIntentFilter.matchAction(intent.getAction())) {
            notifyBatteryStatus(getBatteryStatus(intent));
        }
    }

    private void notifyBatteryStatus(BatteryStatus batteryStatus) {
        eventBus.postSticky(new BatteryStatusChangeEvent(batteryStatus));
    }

    private BatteryStatus getBatteryStatus(@NonNull Intent batteryStatusIntent) {
        int status = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        ChargeLevel chargeLevel;
        if (status == BatteryManager.BATTERY_STATUS_FULL) {
            chargeLevel = ChargeLevel.FULL;
        } else {
            int level = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            float chargePercent = level / (float) scale;
            chargeLevel = fromPercentage(chargePercent);
        }

        return new BatteryStatus(chargeLevel, isCharging);
    }

    private static ChargeLevel fromPercentage(float percentage) {
        if (percentage < 0.2f)
            return ChargeLevel.CRITICAL;
        else if (percentage < 0.333f)
            return ChargeLevel.LEVEL_1;
        else if (percentage < 0.666f)
            return ChargeLevel.LEVEL_2;
        else if (percentage < 1.0f)
            return ChargeLevel.LEVEL_3;
        else
            return ChargeLevel.FULL;
    }
}
