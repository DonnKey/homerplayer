/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2018-2019 Donn S. Terry
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
package com.donnKey.aesopPlayer.ui.provisioning;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;
import de.greenrobot.event.EventBus;

import android.view.MenuItem;
import android.widget.Toast;

import com.google.common.base.Preconditions;
import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.GlobalSettings;
import com.donnKey.aesopPlayer.KioskModeSwitcher;
import com.donnKey.aesopPlayer.R;
import com.donnKey.aesopPlayer.events.MediaStoreUpdateEvent;
import com.donnKey.aesopPlayer.model.AudioBook;
import com.donnKey.aesopPlayer.ui.OrientationActivityDelegate;
import com.donnKey.aesopPlayer.ui.settings.SettingsActivity;

import java.io.File;
import java.util.Objects;

import javax.inject.Inject;

import static com.donnKey.aesopPlayer.AesopPlayerApplication.getAppContext;
import static com.donnKey.aesopPlayer.ui.settings.SettingsActivity.setMenuItemProperties;

public class ProvisioningActivity extends AppCompatActivity
{
    @Inject
    public GlobalSettings globalSettings;
    @Inject
    public KioskModeSwitcher kioskModeSwitcher;

    public final static String EXTRA_TARGET_FRAGMENT = "com.donnKey.aesopPlayer.RETURN_MESSAGE";
    private final static int ACTIVITY_REQUEST_PROVISIONING = 1235;

    private OrientationActivityDelegate orientationDelegate;

    private Provisioning provisioning;
    BottomNavigationView navigation;

    public ProvisioningActivity() {
        // required stub constructor
    }

    CandidateFragment activeCandidateFragment;
    InventoryItemFragment activeInventoryFragment;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AesopPlayerApplication.getComponent(getAppContext()).inject(this);
        super.onCreate(savedInstanceState);

        provisioning = ViewModelProviders.of(this).get(Provisioning.class);

        orientationDelegate = new OrientationActivityDelegate(this, globalSettings);

        setContentView(R.layout.activity_provisioning);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        navigation = findViewById(R.id.navigation);
        navigation.setOnNavigationItemSelectedListener(this::onNavigationItemSelectedListener);
        navigation.setItemIconTintList(null); // enable my control of icon color

        if (provisioning.currentFragment == 0) {
            navigation.setSelectedItemId(R.id.navigation_settings);
        }

        ActionBar actionBar = getSupportActionBar();
        Preconditions.checkNotNull(actionBar);
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    // Used to tell MainActivity that we're here on purpose.
    private static boolean doingProvisioning;

    @Override
    protected void onStart() {
        doingProvisioning = true;
        super.onStart();
        orientationDelegate.onStart();
    }

    @Override
    protected void onStop() {
        doingProvisioning = false;
        orientationDelegate.onStop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = globalSettings.isMaintenanceMode();
        setMenuItemProperties(this, navigation.getMenu().getItem(3),
                enabled ? R.drawable.ic_settings_red_24dp : R.drawable.ic_settings_redish_24dp,
                enabled ? android.R.color.white : R.color.medium_dark_grey);
    }

    static public boolean getInProvisioning() {
        return doingProvisioning;
    }

    private boolean onNavigationItemSelectedListener (MenuItem item) {
        switch (item.getItemId()) {
        case R.id.navigation_inventory:
            provisioning.currentFragment = item.getItemId();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.inventory_container, new InventoryItemFragment())
                    .commit();
            return true;

        case R.id.navigation_candidates:
            provisioning.currentFragment = item.getItemId();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.inventory_container, new CandidateFragment())
                    .commit();
            return true;

        case R.id.navigation_settings:
            Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
            startActivityForResult(intent, ACTIVITY_REQUEST_PROVISIONING);
            return true;

        case R.id.navigation_maintenance:
            boolean enabled = !globalSettings.isMaintenanceMode();
            setMenuItemProperties(this, item,
                    enabled ? R.drawable.ic_settings_red_24dp : R.drawable.ic_settings_redish_24dp,
                    enabled ? android.R.color.white : R.color.medium_dark_grey);
            kioskModeSwitcher.setKioskMaintenanceMode(this, enabled);
            globalSettings.setMaintenanceMode(enabled);
            return true;
        }
        return false;
    }

    // Make the back button pop the stack rather than go all the way back to MainActivity.
    // The back button in the sub-fragments is owned by this fragment, so the code below goes here.
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        //noinspection SwitchStatementWithTooFewBranches
        switch (item.getItemId()) {
        case android.R.id.home:
            FragmentManager fragmentManager = getSupportFragmentManager();
            if (fragmentManager.getBackStackEntryCount() > 0 ){
                fragmentManager.popBackStack();
                return true;
            }
            break;
        default:
            break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void switchTo(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.inventory_container, fragment)
            .addToBackStack(null)
            .commit();
    }

    public void updateTitle(Provisioning.Candidate item) {
        provisioning.fragmentParameter = item;
        switchTo(new TitleEditFragment());
    }

    public void updateTitle(AudioBook book) {
        provisioning.fragmentParameter = book;
        switchTo(new TitleEditFragment());
    }

    public void updateProgress(AudioBook book) {
        provisioning.fragmentParameter = book;
        switchTo(new PositionEditFragment());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ACTIVITY_REQUEST_PROVISIONING) {
            if (resultCode == RESULT_OK) {
                int newViewId = Objects.requireNonNull(data).getIntExtra(EXTRA_TARGET_FRAGMENT, 0);
                navigation.setSelectedItemId(newViewId);
                return;
            }
            else if (resultCode == RESULT_CANCELED) {
                finish();
                return;
            }
        }

        if ((requestCode&0xFFFF) == CandidateFragment.REQUEST_DIR_LOOKUP) {
            // onActivityResult in the fragment should be being called, but it's not, sometimes.
            // So do it here (callee will have to look for double calls.) (Seen on Oreo, not on others).
            Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.inventory_container);
            Preconditions.checkNotNull(fragment);
            fragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void postResults(String title) {
        if (provisioning.errorLogs.size() <= 0) {
            return;
        }
        CheckLoop:
        //noinspection ConstantConditions,LoopStatementThatDoesntLoop
        do {
            for (Provisioning.ErrorInfo e : provisioning.errorLogs) {
                switch (e.severity) {
                case INFO:
                    continue;
                case MILD:
                case SEVERE:
                    break CheckLoop;
                }
            }
            return;
        } while(false);

        provisioning.errorTitle = title;
        switchTo(new ErrorTextFragment());
    }

    private boolean retainBooks;
    private Toast lastToast;
    @UiThread
    private void postMoveProgress_Inner(Provisioning.ProgressKind kind, String message) {
        switch (kind) {
        case SEND_TOAST: {
            if (lastToast != null)
                lastToast.cancel();
            lastToast = Toast.makeText(getAppContext(), message, Toast.LENGTH_SHORT);
            lastToast.show();
            break;
        }
        case FILESYSTEMS_FULL: {
            new AlertDialog.Builder(getAppContext())
                    .setTitle(getString(R.string.error_dialog_title_file_system_full))
                    .setIcon(R.drawable.ic_launcher)
                    .setMessage(getString(R.string.error_dialog_file_system_full))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            break;
        }
        case BOOK_DONE: {
            if (activeCandidateFragment != null) activeCandidateFragment.notifyDataSetChanged();
            EventBus.getDefault().post(new MediaStoreUpdateEvent());
            break;
        }
        case ALL_DONE: {
            EventBus.getDefault().post(new MediaStoreUpdateEvent());
            if (!retainBooks) {
                // Rebuild everything (if needed)
                if (activeCandidateFragment != null) {
                    activeCandidateFragment.buildCandidateList();
                    activeCandidateFragment.notifyDataSetChanged();
                }
            }
            postResults(getAppContext().getString(R.string.fragment_title_errors_new_books));
            break;
        }
        }
    }

    @WorkerThread
    private void postMoveProgress(Provisioning.ProgressKind kind, String message) {
        runOnUiThread(()->postMoveProgress_Inner(kind,message));
    }

    @WorkerThread
    private void moveAllSelected_Task() {
        if (activeCandidateFragment != null) activeCandidateFragment.stopChecker();
        provisioning.moveAllSelected_Task(this::postMoveProgress, retainBooks);
        if (activeCandidateFragment != null) activeCandidateFragment.startChecker();
    }

    @UiThread
    void moveAllSelected() {
        retainBooks = globalSettings.getRetainBooks() && provisioning.candidateDirectory.canWrite();
        Thread t = new Thread(this::moveAllSelected_Task);
        t.start();
    }

    @UiThread
    void groupAllSelected() {
        File newDir = null;

        for (Provisioning.Candidate c: provisioning.candidates) {
            if (c.isSelected) {
                File bookPath = new File(c.oldDirPath);
                if (newDir == null) {
                    File baseFile = new File(c.oldDirPath);
                    String baseName = baseFile.getName();
                    int extensionPos = baseName.lastIndexOf('.');
                    if (extensionPos > 0) {
                        baseName = baseName.substring(0, extensionPos);
                    }
                    baseName += ".group";
                    newDir = new File(bookPath.getParentFile(), baseName);

                    if (newDir.exists()) {
                        new AlertDialog.Builder(getApplicationContext())
                                .setTitle(getString(R.string.dialog_title_group_books))
                                .setIcon(R.drawable.ic_launcher)
                                .setMessage(getString(R.string.dialog_colliding_group))
                                .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {})
                                .show();
                        return;
                    } else if (!newDir.mkdirs()) {
                        new AlertDialog.Builder(getApplicationContext())
                                .setTitle(getString(R.string.dialog_title_group_books))
                                .setIcon(R.drawable.ic_launcher)
                                .setMessage(getString(R.string.dialog_cannot_make_group))
                                .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {})
                                .show();
                        return;
                    }
                }

                String renamedTo = c.newDirName;
                if (!bookPath.isDirectory()) {
                    // This is a zip or audio file, retain the extension
                    // (We couldn't get here if it wasn't one of those because it's not a candidate)

                    String bookDirName = bookPath.getName();
                    String oldExtension = "";
                    int dotLoc = bookDirName.lastIndexOf('.');
                    oldExtension = bookDirName.substring(dotLoc);
                    renamedTo += oldExtension;

                    // deblank in case it gets ungrouped - the result would be messy otherwise
                    renamedTo = AudioBook.deBlank(renamedTo);
                }

                File toBook = new File(newDir, renamedTo);
                if (!bookPath.renameTo(toBook)) {
                    new AlertDialog.Builder(getApplicationContext())
                            .setTitle(getString(R.string.dialog_title_group_books))
                            .setIcon(R.drawable.ic_launcher)
                            .setMessage(String.format(getString(R.string.dialog_cannot_rename_group), bookPath.getPath(), toBook.getPath()))
                            .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {})
                            .show();
                }
            }
        }
    }

    @UiThread
    void unGroupSelected() {
        for (Provisioning.Candidate c: provisioning.candidates) {
            if (c.isSelected) {
                File ungroupDir = new File(c.oldDirPath);
                File parent = ungroupDir.getParentFile();
                for (String fn : ungroupDir.list()) {
                    File newLoc = new File(parent, fn);
                    File oldLoc = new File(ungroupDir,fn);
                    int n = 0;
                    while (!oldLoc.renameTo(newLoc)) {
                        if (n++ > 3) {
                            break;
                        }
                        new AlertDialog.Builder(getApplicationContext())
                                .setTitle(getString(R.string.dialog_title_ungroup_book))
                                .setIcon(R.drawable.ic_launcher)
                                .setMessage(String.format(getString(R.string.dialog_cannot_rename_group), oldLoc.getPath(), newLoc.getPath()))
                                .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {})
                                .show();
                        fn += ".collision";
                        newLoc = new File(parent, fn);
                    }
                }
                // Unless we couldn't empty the directory, this wouldn't fail. If we couldn't empty
                // it, we know that from failures above. Nothing useful to do here.
                //noinspection ResultOfMethodCallIgnored
                ungroupDir.delete();
                break;
            }
        }
    }

    @UiThread
    private void postDeleteProgress_Inner(Provisioning.ProgressKind kind, @SuppressWarnings("unused") String message) {
        switch (kind) {
        case SEND_TOAST:
        case FILESYSTEMS_FULL: {
            throw new RuntimeException("Bad Progress Value");
        }
        case BOOK_DONE: {
            if (activeInventoryFragment != null) activeInventoryFragment.notifyDataSetChanged();
            break;
        }
        case ALL_DONE: {
            EventBus.getDefault().post(new MediaStoreUpdateEvent());
            provisioning.buildBookList();
            if (activeInventoryFragment != null) {
                activeInventoryFragment.refreshSubtitle();
                activeInventoryFragment.notifyDataSetChanged();
            }
            postResults(getAppContext().getString(R.string.fragment_title_errors_deleting_books));
            break;
        }
        }
    }

    @WorkerThread
    private void postDeleteProgress(Provisioning.ProgressKind kind, String message) {
        runOnUiThread(()->postDeleteProgress_Inner(kind,message));
    }

    @UiThread
    void deleteAllSelected() {
        Thread t = new Thread(this::deleteAllSelected_Task);
        t.start();
    }

    private void deleteAllSelected_Task() {
        provisioning.deleteAllSelected_Task(this::postDeleteProgress);
    }
}
