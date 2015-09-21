package com.studio4plus.homerplayer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;

import java.net.URL;
import java.util.Locale;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import de.greenrobot.event.EventBus;

@Module
public class ApplicationModule {

    private final Application application;
    private final URL samplesDownloadUrl;

    public ApplicationModule(Application application, URL samplesDownloadUrl) {
        this.application = application;
        this.samplesDownloadUrl = samplesDownloadUrl;
    }

    @Provides
    Context provideContext() {
        return application;
    }

    @Provides
    Resources provideResources(Context context) {
        return context.getResources();
    }

    @Provides
    Locale provideCurrentLocale(Resources resources) {
        return resources.getConfiguration().locale;
    }

    @Provides
    SharedPreferences provideSharedPreferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Provides @Singleton @Named("SAMPLES_DOWNLOAD_URL")
    URL provideSamplesUrl() {
        return samplesDownloadUrl;
    }

    @Provides
    EventBus provideEventBus() {
        // TODO: provide the EventBus to all classes via Dagger and then switch to a private instance.
        return EventBus.getDefault();
    }

    @Provides @Singleton
    GoogleAnalytics provideGoogleAnalytics(Context applicationContext) {
        return GoogleAnalytics.getInstance(applicationContext);
    }

    @Provides @Singleton
    Tracker provideGoogleAnalyticsTracker(GoogleAnalytics googleAnalytics) {
        return googleAnalytics.newTracker(R.xml.global_tracker);
    }
}