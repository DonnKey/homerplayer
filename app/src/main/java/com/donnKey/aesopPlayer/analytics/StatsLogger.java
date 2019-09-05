package com.donnKey.aesopPlayer.analytics;

import android.content.Context;
import androidx.annotation.NonNull;
/*
import android.content.res.AssetManager;
import androidx.annotation.Nullable;

import com.flurry.android.FlurryAgent;
import com.donnKey.aesopPlayer.util.VersionUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
 */
import java.util.Map;

// Flurry is a product for gathering customer demographic data.
// Currently Flurry is disabled rather than deal with the legal issues involved.
// All functions are no-ops. No Flurry account exists.
// There is no assets/api_keys/flurry in GIT; one would need to be created after siging up with Flurry
// App build.gradle would also need to be uncommented
class StatsLogger {

    private static final String FLURRY_API_KEY_ASSET = "api_keys/flurry";
    private boolean isFlurryEnabled;

    StatsLogger(@NonNull Context context) {
        /*
        String flurryKey = getFlurryKey(context.getAssets());
        if (flurryKey != null && VersionUtil.isOfficialVersion()) {
            new FlurryAgent.Builder()
                    .withLogEnabled(true)
                    .build(context, flurryKey);
            isFlurryEnabled = true;
        }
         */
    }

    void logEvent(@NonNull String eventName) {
        /*
        if (isFlurryEnabled) {
            FlurryAgent.logEvent(eventName);
        }
         */
    }

    void logEvent(@NonNull String eventName, @NonNull Map<String, String> eventData) {
        /*
        if (isFlurryEnabled) {
            FlurryAgent.logEvent(eventName, eventData);
        }
         */
    }

    @SuppressWarnings("SameParameterValue")
    void endTimedEvent(@NonNull String eventName) {
        /*
        if (isFlurryEnabled) {
            FlurryAgent.endTimedEvent(eventName);
        }
         */
    }

    /*
    @Nullable
    private static String getFlurryKey(AssetManager assets) {
        try {
            InputStream inputStream = assets.open(FLURRY_API_KEY_ASSET);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String key = reader.readLine();
                inputStream.close();
                return key;
            } catch(IOException e) {
                inputStream.close();
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }
     */
}