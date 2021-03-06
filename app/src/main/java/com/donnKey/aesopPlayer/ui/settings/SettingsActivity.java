/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018-2020 Donn S. Terry
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
package com.donnKey.aesopPlayer.ui.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.material.bottomnavigation.BottomNavigationItemView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.common.base.Preconditions;
import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.GlobalSettings;
import com.donnKey.aesopPlayer.KioskModeSwitcher;
import com.donnKey.aesopPlayer.R;
import com.donnKey.aesopPlayer.events.SettingsEnteredEvent;
import com.donnKey.aesopPlayer.service.DeviceMotionDetector;
import com.donnKey.aesopPlayer.ui.ActivityComponent;
import com.donnKey.aesopPlayer.ui.ActivityModule;
import com.donnKey.aesopPlayer.ui.DaggerActivityComponent;
import com.donnKey.aesopPlayer.ui.KioskModeHandler;
import com.donnKey.aesopPlayer.ui.OrientationActivityDelegate;
import com.donnKey.aesopPlayer.ui.provisioning.ProvisioningActivity;

import java.util.Objects;

import javax.inject.Inject;

import org.greenrobot.eventbus.EventBus;

import static com.donnKey.aesopPlayer.GlobalSettings.TAG_KIOSK_DIALOG;

public class SettingsActivity
        extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {

    private static final int BLOCK_TIME_MS = 500;
    @SuppressWarnings("unused")
    private static final String TAG = "SettingsActivity";

    private Handler mainThreadHandler;
    private Runnable unblockEventsTask;
    private OrientationActivityDelegate orientationDelegate;
    private BottomNavigationView navigation;

    @Inject public EventBus eventBus;
    @Inject public GlobalSettings globalSettings;
    @Inject public KioskModeHandler kioskModeHandler;
    @Inject public KioskModeSwitcher kioskModeSwitcher;
    private static boolean enteringSettings;
    private final Handler handler = new Handler();

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ActivityComponent activityComponent = DaggerActivityComponent.builder()
                .applicationComponent(AesopPlayerApplication.getComponent())
                .activityModule(new ActivityModule(this))
                .build();
        activityComponent.inject(this);

        setTheme(globalSettings.colorTheme().styleId);
        setContentView(R.layout.settings_activity);
        super.onCreate(savedInstanceState);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        Preconditions.checkNotNull(Objects.requireNonNull(actionBar));
        actionBar.setDisplayHomeAsUpEnabled(true);

        navigation = findViewById(R.id.navigation);
        navigation.setOnNavigationItemSelectedListener(this::onNavigationItemSelectedListener);
        navigation.setItemIconTintList(null); // enable my control of icon color
        navigation.setSelectedItemId(R.id.navigation_settings); // for side-effect of item size change

        // On Pie and later with the new rotation stuff, the image is displayed in portrait and
        // then immediately rotated. There's now a line in AndroidManifest that prevents that.
        // There doesn't seem to be a way to tell the activity soon enough programmatically.
        // (Main has the same problem and fix, but it's much less irritating.)
        orientationDelegate = new OrientationActivityDelegate(this, globalSettings);

        // Display the fragment as the main content.
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, new MainSettingsFragment())
                .commit();
        mainThreadHandler = new Handler(getMainLooper());
    }

    private boolean onNavigationItemSelectedListener (@NonNull MenuItem item) {
        switch (item.getItemId()) {
        case R.id.navigation_inventory:
        case R.id.navigation_candidates:
            Intent intent = new Intent(getApplicationContext(), ProvisioningActivity.class);
            intent.putExtra(ProvisioningActivity.EXTRA_TARGET_FRAGMENT,item.getItemId());
            setResult(RESULT_OK,intent);
            finish();
            return true;

        case R.id.navigation_settings:
            // If we're not in the main settings fragment, go there when the user clicks Settings.
            // (Yeah, it's really just 'back', but it feels like a bug to just ignore it.)
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.settings_container);
            if (currentFragment != null && !(currentFragment instanceof MainSettingsFragment)) {
                getSupportFragmentManager().popBackStack();
            }

            // No-op
            return true;

        case R.id.navigation_maintenance:
            boolean enabled = !globalSettings.isMaintenanceMode();
            setMenuItemProperties(this, item,
                    enabled ? R.drawable.ic_settings_red_24dp : R.drawable.ic_settings_redish_24dp,
                    enabled ? android.R.color.white : R.color.medium_dark_grey);
            KioskModeSwitcher.enableMaintenanceMode(this, enabled);
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        orientationDelegate.onStart();
        blockEventsOnStart();
        eventBus.post(new SettingsEnteredEvent());
        enteringSettings = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        kioskModeSwitcher.switchToNoKioskMode(this);

        boolean enabled = globalSettings.isMaintenanceMode();
        setMenuItemProperties(this, navigation.getMenu().getItem(3),
                enabled ? R.drawable.ic_settings_red_24dp : R.drawable.ic_settings_redish_24dp,
                enabled ? android.R.color.white : R.color.medium_dark_grey);

        DeviceMotionDetector.suspend();
    }

    @Override
    protected void onPause() {
        // Sometimes onStop can be delayed by as much as 10 seconds (others have noted that;
        // see StackOverflow). That makes it possible to hit the gear icon on BookList and
        // restart settings while enteringSettings would be true if we also didn't also do this.
        // If this isn't done, hitting the gear icon causes a blank settings screen!
        // onPause alone occurs too soon and causes a new Settings Screen to be posted
        // by ProvisioningActivity#onNavigationItemSelectedListener, requiring another Back
        // to return to book list. (N.B. post() doesn't delay long enough.)
        if (isFinishing()) {
            handler.postDelayed(()->enteringSettings = false, 250);
        }
        super.onPause();
    }

    static public boolean getInSettings() {
        return enteringSettings;
    }

    @Override
    protected void onStop() {
        enteringSettings = false;
        super.onStop();
        orientationDelegate.onStop();
        cancelBlockEventOnStart();
        DeviceMotionDetector.resume();
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, @NonNull Preference pref) {
        // Instantiate the new Fragment
        final Bundle args = pref.getExtras();
        final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(
                getClassLoader(), pref.getFragment());
        Preconditions.checkState(fragment instanceof BaseSettingsFragment);

        fragment.setArguments(args);
        fragment.setTargetFragment(caller, 0);
        // Replace the existing Fragment with the new Fragment
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
        return true;
    }

    @Override
    public boolean onPreferenceDisplayDialog(
            @NonNull PreferenceFragmentCompat preferenceFragmentCompat, Preference preference) {
        if (preference instanceof ConfirmDialogPreference) {
            DialogFragment dialogFragment =
                    ConfirmDialogFragmentCompat.newInstance(preference.getKey());
            dialogFragment.setTargetFragment(preferenceFragmentCompat, 0);
            dialogFragment.show(getSupportFragmentManager(), "CONFIRM_DIALOG");
            return true;
        }
        if (preference instanceof KioskSelectionPreference) {
            DialogFragment dialogFragment =
                    KioskSelectionFragmentCompat.newInstance(preference.getKey());
            dialogFragment.setTargetFragment(preferenceFragmentCompat, 0);
            dialogFragment.show(getSupportFragmentManager(), TAG_KIOSK_DIALOG);
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //noinspection SwitchStatementWithTooFewBranches
        switch(item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void blockEventsOnStart() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        unblockEventsTask = () -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            unblockEventsTask = null;
        };
        mainThreadHandler.postDelayed(unblockEventsTask, BLOCK_TIME_MS);
    }

    private void cancelBlockEventOnStart() {
        if (unblockEventsTask != null)
            mainThreadHandler.removeCallbacks(unblockEventsTask);
    }

    @Override
    protected void onActivityResult( int requestCode, int resultCode, Intent data) {
        kioskModeHandler.onActivityResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        kioskModeHandler.onRequestPermissionResult(requestCode, permissions, grantResults);
    }

    static public void setMenuItemProperties(@NonNull AppCompatActivity activity,
                                             @NonNull MenuItem item,
                                             int resIconDrawable, int resColor) {
        int id = item.getItemId();

        BottomNavigationItemView m = activity.findViewById(id);
        TextView t1 = m.findViewById(R.id.smallLabel);
        TextView t2 = m.findViewById(R.id.largeLabel);
        t1.setTextColor(activity.getResources().getColor(resColor));
        t2.setTextColor(activity.getResources().getColor(resColor));

        Drawable d = VectorDrawableCompat.create(activity.getResources(), resIconDrawable, null);
        //Drawable d = activity.getResources().getDrawable(resIconDrawable);
        item.setIcon(d);
    }
}
