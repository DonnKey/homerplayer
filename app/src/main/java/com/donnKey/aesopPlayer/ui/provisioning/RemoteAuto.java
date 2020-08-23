/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Donn S. Terry
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
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.BuildConfig;
import com.donnKey.aesopPlayer.GlobalSettings;
import com.donnKey.aesopPlayer.analytics.CrashWrapper;
import com.donnKey.aesopPlayer.events.AnAudioBookChangedEvent;
import com.donnKey.aesopPlayer.events.AudioBooksChangedEvent;
import com.donnKey.aesopPlayer.events.MediaStoreUpdateEvent;
import com.donnKey.aesopPlayer.model.AudioBook;
import com.donnKey.aesopPlayer.model.AudioBookManager;
import com.donnKey.aesopPlayer.ui.UiControllerBookList;
import com.donnKey.aesopPlayer.ui.UiUtil;
import com.donnKey.aesopPlayer.ui.settings.RemoteSettingsFragment;
import com.donnKey.aesopPlayer.util.AwaitResume;
import com.donnKey.aesopPlayer.util.FilesystemUtil;
import com.google.android.gms.common.internal.Asserts;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import de.greenrobot.event.EventBus;

import static com.donnKey.aesopPlayer.AesopPlayerApplication.WEBSITE_URL;
import static com.donnKey.aesopPlayer.AesopPlayerApplication.getAppContext;
import static com.donnKey.aesopPlayer.service.DemoSamplesInstallerService.enableTlsOnAndroid4;
import static com.donnKey.aesopPlayer.util.FilesystemUtil.isAudioPath;

//???????? A singleton?
public class RemoteAuto {
    // Every *interval* check if there's a new instance of the control file or new mail, and if so
    // perform the actions directed by that file.  The parser in process() defines what
    // it can do. It always checks things on first startup (which is when the player goes
    // to bookList (idle)), but doesn't run outdated requests.

    // Note: setting up jekyll serve to serve test files is really easy as long as you want
    // http, not https (you want "clear text"). jekyll serve with certificates is a pain.
    // but to use cleartext, you have to have network_security_config.xml set
    // to allow cleartext; see that file.

    @Inject
    public GlobalSettings globalSettings;
    @Inject
    public AudioBookManager audioBookManager;
    @Inject
    @Named("AUDIOBOOKS_DIRECTORY")
    public String audioBooksDirectoryName;
    @Inject
    public EventBus eventBus;

    @SuppressWarnings("FieldCanBeLocal")
    private final String controlFileName = "AesopScript.txt";
    @SuppressWarnings("FieldCanBeLocal")
    private final String resultFileName = "AesopResult.txt";

    private final Context appContext;
    private final Provisioning provisioning;
    private final File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    private final Handler handler;
    private final String TAG="RemoteAuto";
    private final AwaitResume booksChanged = new AwaitResume();
    private final AwaitResume downloadCompletes = new AwaitResume();
    private final AwaitResume booksModifiedUpdateComplete = new AwaitResume();
    private final AwaitResume booksPreUseUpdateComplete = new AwaitResume();
    private final static String TAG_WORK = "Remote Auto";
    private final List<String> singleRequestResultLog = new ArrayList<>();
    private final List<String> compositeResultLog = new ArrayList<>();
    private final ArrayList<String> sendResultTo = new ArrayList<>();
    private final static int DOWNLOAD_BUFFER_SIZE = 32768;
    private DownloadManager downloadManager;

    private boolean continueProcessing;
    private boolean deleteMessage;
    private boolean generateReport;
    private Calendar processingStartTime;
    private boolean firstTime = true;

    File controlDir;
    File currentCandidateDir;
    boolean candidatesIsAudioBooks; // above File is currently (an) AudioBooks dir.
    boolean audioBooksBeingChanged; // we're making changes to an audioBooks dir right now.
    int unSizedBooksRemaining;
    String downloadedString; // The file that got the download: null if download isn't complete or unsuccessful

    // Per request state
    private boolean retainBooks;
    private boolean archiveBooks;
    private boolean renameFiles;
    private boolean allowMobileData;
    private boolean useDownloadManager;
    private boolean forceDownloadManager;
    private Calendar messageSentTime;
    private int lineCounter; // counts non-comment lines

    // For debugging
    @SuppressWarnings({"FieldCanBeLocal", "CanBeFinal"})
    private boolean consoleLogReport = false;
    @SuppressWarnings("CanBeFinal")
    private boolean consoleLog = false;

    //?????????????? Really a singleton?
    @Singleton
    @UiThread
    public RemoteAuto() {
        appContext = getAppContext();
        AesopPlayerApplication.getComponent(appContext).inject(this);

        try {
            // This is required but one-time only, but we might be running on the same thread
            // a previous incarnation had used, so just ignore it.
            Looper.prepare();
        } catch (RuntimeException e) {
            // ignore
        }
        handler = new Handler();

        this.provisioning = Provisioning.getInstance();
        eventBus.register(this);
        if (BuildConfig.DEBUG) {
            // If debugging, this will always process the shared file at startup (once), for testing.
            //?????????????????????????????
            globalSettings.setSavedControlFileTimestamp(0);
            consoleLog = true;
            //consoleLogReport = true;
        }
    }

    public void pollSources() {
        if (consoleLog) {
            Log.v("AESOP " + getClass().getSimpleName() + " "+ android.os.Process.myPid() + " "
                    + Thread.currentThread().getId(),
                    "Poll cycle ========================================================");
        }

        if (firstTime) {
            // This often occurs very early in startup because of the way periodic work is scheduled.
            // Skip the first time.
            firstTime = false;
            return;
        }

        if (RemoteSettingsFragment.getInRemoteSettings()) {
            return; // we'll try again wen it's not busy
        }
        compositeResultLog.clear();
        sendResultTo.clear();

        if (globalSettings.getFilePollEnabled()) {
            pollControlFile();
        }

        if (globalSettings.getMailPollEnabled()) {
            pollMail();
        }

        sendFinalReport();

        //???????????? should I: activity.unbindService(this); (in provisioning, for playback)?
        //????????? part of search for unclosed resource?
        downloadManager = null;
    }

    @WorkerThread
    private void pollControlFile() {
        continueProcessing = true;
        deleteMessage = true;
        processingStartTime = Calendar.getInstance();
        controlDir = new File(globalSettings.getRemoteControlDir());

        File controlFile = new File(controlDir, controlFileName);
        if (!controlFile.exists()) {
            return;
        }

        long timestamp = controlFile.lastModified();
        if (timestamp <= globalSettings.getSavedControlFileTimestamp()) {
            Calendar nextAtTime = globalSettings.getSavedAtTime();
            if (nextAtTime.after(processingStartTime)) {
                return;
            }
        }

        // The file has been changed since last we looked.
        BufferedReader commands;
        try {
            commands = new BufferedReader(new FileReader(controlFile));
        } catch (FileNotFoundException e) {
            CrashWrapper.recordException(TAG, e);
            return;
        }

        messageSentTime = Calendar.getInstance();
        messageSentTime.setTimeInMillis(timestamp);

        startReport();
        processCommands(commands);
        endReport();

        try {
            commands.close();
        } catch (IOException e) {
            CrashWrapper.recordException(TAG, e);
        }

        if (deleteMessage) {
            // We can't actually delete it, but we can ignore it until the file is changed
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.YEAR, 100);
            setEarliestSavedAtTime(cal);
        }

        globalSettings.setSavedControlFileTimestamp(timestamp);
    }

    //?????? revisit
    static Calendar lastRun = null;
    @WorkerThread
    private void pollMail() {
        // AFAICT everything done here, and the installs, will time out with an error
        // if something goes wrong, so we don't need to worry about timeouts here.
        controlDir = new File(globalSettings.getRemoteControlDir());
        Mail mail = new Mail();

        if (mail.open() != Mail.SUCCESS) {
            // The interval between polls is long enough that a back-off is pointless.
            // (Note: We can't get here unless it worked once, so this is probably
            // either an external login change or some external connection problem.)
            return;
        }

        // Search is for a pattern, case insensitive
        if (mail.readMail() != Mail.SUCCESS) {
            return;
        }

        for(Mail.Request request:mail) {
            continueProcessing = true;
            deleteMessage = true;
            processingStartTime = Calendar.getInstance();

            messageSentTime = Calendar.getInstance();
            messageSentTime.setTime(request.sentTime());

            // Can't ignore old mail because it might be an "every"... those
            // could hang around for years. We rely on deletion of ephemeral mail

            // ??????????? DON'T DO THIS WHEN IN PRODUCTION
            // ??????? Thus delete lastRun
            Calendar yesterday = (Calendar) processingStartTime.clone();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);

            if (messageSentTime.before(yesterday)) {
                // more than a day old... ignore
                Log.w("AESOP " + getClass().getSimpleName(), "Ignoring old really old");
                continue;
            }
            if (lastRun != null && lastRun.after(messageSentTime))  {
                Log.w("AESOP " + getClass().getSimpleName(), "SKIP OLD MAIL");
                continue;
            }
            /*
            if (sentTime <= globalSettings.getSavedMailTimestamp()) {
                Log.w("AESOP " + getClass().getSimpleName(), "SKIP OLD MAIL");
                continue;
            }
             */

            BufferedReader commands = request.getMessageBodyStream();
            lastRun = processingStartTime;
            // If there's no plain-text MIME body section, we'll get null here.
            if (commands == null) {
                continue;
            }

            startReport();
            processCommands(commands);
            if (endReport()) {
                // send results to senders only for requests we actually processed
                if (!sendResultTo.contains(request.getMessageSender())) {
                    sendResultTo.add(request.getMessageSender());
                }
            }

            if (deleteMessage) {
                request.delete();
            }
        }

        mail.close();
    }


    @SuppressLint("DefaultLocale")
    @WorkerThread
    private void processCommands(BufferedReader commands) {
        // Reset to the same initial state each cycle
        // Start downloads in the same place each run
        currentCandidateDir = downloadDir;
        candidatesIsAudioBooks = false;
        singleRequestResultLog.clear();
        lineCounter = 0;

        // "Almost always" defaults for installing
        retainBooks = false;
        archiveBooks = false;
        renameFiles = true;
        allowMobileData = false;
        useDownloadManager = false;
        forceDownloadManager = false;

        logActivity("Start of request " + getDeviceTag() + " at " + processingStartTime.getTime());

        // Read and process each line of the input stream.
        while (continueProcessing) {
            String line;
            try {
                line = commands.readLine();
            } catch (IOException e) {
                Log.w("AESOP " + getClass().getSimpleName(), "EOF catch");
                return;
            }

            if (line == null) {
                return;
            }
            logActivity(line);

            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Split on spaces, honoring quoted strings correctly, including handling
            // of escaped quotes. Since NUL is illegal in a filename...
            line = line.replace("\\\"", "\000");
            ArrayList<String> operands = new ArrayList<>(Arrays.asList(
                    line.split(" +(?=([^\"]*\"[^\"]*\")*[^\"]*$)")));
            int count;
            for (count = 0; count < operands.size(); count++) {
                if (operands.get(count).indexOf("//") == 0) {
                    // a comment
                    break;
                }
                operands.set(count, operands.get(count).replace("\000", "\""));
            }

            // Trim the comment words
            while (operands.size() > count) {
                operands.remove(operands.size() - 1);
            }

            if (operands.size() == 0) {
                continue;
            }

            String op0 = operands.get(0);
            int pos = op0.indexOf(':');
            if (pos < 0) {
                logActivityIndented("Command not recognized (missing ':')");
                continue;
            }

            provisioning.clearErrors();
            lineCounter++;

            String key = op0.substring(0, pos + 1).toLowerCase();
            switch (key) {
                case "file:":
                case "ftp:":
                case "http:":
                case "https:": {
                    // Download and/or install a file
                    boolean downloadOnly = checkOperandsFor(operands, "downloadOnly");
                    String newTitle = findOperandString(operands);
                    if (newTitle != null) {
                        newTitle = checkName(newTitle);
                        if (newTitle == null) {
                            break;
                        }
                    }

                    if (errorIfAnyRemaining(operands)) {
                        break;
                    }

                    switch (key) {
                        case "https:":
                            if (Build.VERSION.SDK_INT <= 21) {
                                // If it's prior to L, the default download manager can't handle
                                // modern https (TLS 1.3 or greater).
                                if (useDownloadManager && !forceDownloadManager) {
                                    logActivityIndented(
                                        "https connections using a download manager do not work on this device. "
                                        + "See " + WEBSITE_URL + "provisioning.html#settings-manager" );
                                    break;
                                }
                            }
                            // drop-thru
                        case "http:": {
                            if (!isWiFiEnabled() && !allowMobileData) {
                                logActivityIndented("Not connected to WiFi and Mobile Data not allowed.");
                                break;
                            }

                            if (downloadOnly && newTitle != null) {
                                logActivityIndented("Cannot use 'downloadOnly' with new title");
                                break;
                            }

                            if (!checkURLValidity(op0)) {
                                // Above function displays errors
                                break;
                            }

                            String uri;
                            long ticks = System.nanoTime();
                            if (useDownloadManager) {
                                // Use the download manager in the hope that it's smarter and faster.
                                uri = downloadUsingManager(op0, newTitle);
                            }
                            else {
                                // Use simple sockets. See above about https: on early devices.
                                uri = downloadUsingSockets(op0, newTitle);
                            }
                            ticks = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - ticks);
                            if (uri == null) {
                                break;
                            }
                            logActivityIndented(String.format("Download time was: %dm%02ds", ticks/60, ticks%60));

                            if (downloadOnly) {
                                logActivityIndented("Download only of " + op0 + " as " + uri + " successful.");
                                break;
                            }

                            install(uri, newTitle, true);
                            break;
                        }
                        case "ftp:": {
                            //??????????? ignoring ftp for now
                            logActivityIndented("Error: ftp:// not supported");
                            break;
                        }
                        case "file:": {
                            // A local file.
                            if (downloadOnly) {
                                logActivityIndented("Error: downloadOnly illegal on file://");
                            } else {
                                install(op0, newTitle, false);
                            }
                            break;
                        }
                    }
                    break;
                }
                case "books:": {
                    // Do inventory related commands
                    booksCommands(operands);
                    break;
                }
                case "downloads:": {
                    // Do download related commands
                    downloadsCommands(operands);
                    break;
                }
                case "settings:": {
                    // Do settings related commands
                    settingsCommands(operands);
                    break;
                }
                case "run:": {
                    runCommands(operands);
                    break;
                }
                case "mailto:": {
                    Mail mail = new Mail();
                    if (mail.testConnection() != Mail.SUCCESS) {
                        logActivityIndented("Mail connection not set up: mailto: ignored");
                        break;
                    }
                    String to = op0.substring("mailto:".length());
                    if (!sendResultTo.contains(to)) {
                        sendResultTo.add(to);
                    }
                    logActivityIndented("Results will be mailed to " + to);
                    break;
                }
                case "exit:": {
                    return;
                }
                default: {
                    logActivityIndented("Error: unrecognized command ignored: " + key);
                    break;
                }
            }
        }
    }

    // Downloading stuff

    @WorkerThread
    private String downloadUsingManager(String requested, String newTitle) {
        downloadCompletes.prepare();
        downloadedString = null;
        downloadUsingManager_start(requested, newTitle);
        downloadCompletes.await();
        return downloadedString;
    }

    @WorkerThread
    private boolean isWiFiEnabled()
    {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert(connectivityManager != null);

        boolean enabled = false;

        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if(networkInfo != null)
        {
            if(networkInfo.isConnected()) {
                enabled = networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
            }
        }

        return enabled;
    }

    @WorkerThread
    private boolean checkURLValidity(String requested) {
        Uri uri = Uri.parse(requested);
        String downloadFile = uri.getLastPathSegment();
        if (downloadFile == null) {
            logActivityIndented("Ill formed URI, could not get target file name");
            return false;
        }

        final URL url;
        try {
            url = new URL(requested);
        } catch (MalformedURLException e) {
            logActivityIndented("URL is incorrectly formed, could not parse.");
            return false;
        }

        HttpURLConnection connection;
        int code;
        try {
            connection = (HttpURLConnection)url.openConnection();
        } catch (IOException e) {
            logActivityIndented("Cannot connect to host.");
            return false;
        }

        try {
            connection.setConnectTimeout(1000);
            // So it opens (and closes!) quickly
            connection.setRequestMethod("HEAD");
            enableTlsOnAndroid4(connection);
            code = connection.getResponseCode();
            connection.disconnect();
        } catch (IOException e) {
            connection.disconnect();
            logActivityIndented("Cannot connect to host.");
            return false;
        }

        if (code == 200) {
            return true;
        }

        if (code == 404) {
            logActivityIndented("Target file not found.");
            return false;
        }

        logActivityIndented("Networking error " + code);
        return false;
    }

    @WorkerThread
    private String downloadUsingSockets(String requested, String newTitle) {
        Uri uri = Uri.parse(requested);

        byte[] inputBuffer = new byte[DOWNLOAD_BUFFER_SIZE];
        String downloadFile = (newTitle != null ? newTitle : uri.getLastPathSegment());
        assert(downloadFile != null);
        File tmpFile = FilesystemUtil.createUniqueFilename(currentCandidateDir,downloadFile);
        if (tmpFile == null) {
            logActivityIndented("Too many identical download files: delete them");
            return null;
        }

        final URL url;
        try {
            url = new URL(requested);
        } catch (MalformedURLException e) {
            logActivityIndented("URL is incorrectly formed, could not parse.");
            return null;
        }

        try {
            OutputStream output = new BufferedOutputStream(new FileOutputStream(tmpFile));
            HttpURLConnection connection;
            connection = (HttpURLConnection)url.openConnection();
            enableTlsOnAndroid4(connection);

            // Disable gzip, apparently Java and/or Android's okhttp has problems with it
            // (possibly https://bugs.java.com/bugdatabase/view_bug.do?bug_id=7003462).
            connection.setRequestProperty("accept-encoding", "identity");

            InputStream input = new BufferedInputStream(connection.getInputStream());
            int bytesRead;
            while((bytesRead = input.read(inputBuffer, 0, inputBuffer.length)) > 0) {
                output.write(inputBuffer, 0, bytesRead);
            }
            output.close();
            connection.disconnect();
        } catch (IOException e) {
            Log.w("AESOP " + getClass().getSimpleName(), "download crash");
            CrashWrapper.recordException(TAG, e);
            return null;
        }

        return "file://" + tmpFile.getPath();
    }

    @WorkerThread
    private void downloadUsingManager_start(String requested, String newTitle) {
        Uri uri = Uri.parse(requested);
        String downloadFile = uri.getLastPathSegment();
        assert(downloadFile != null);

        DownloadManager.Request downloadRequest = new DownloadManager.Request(uri);
        downloadRequest
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | (allowMobileData ? DownloadManager.Request.NETWORK_MOBILE : 0))
                .setAllowedOverRoaming(allowMobileData)
                .setDescription("Aesop Player Download")
                .setTitle(newTitle != null ? newTitle : downloadFile)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadFile);

        final long lastDownload = downloadManager.enqueue(downloadRequest);
        // Now we wait for the download to complete.
        IntentFilter intentFilter =
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        //????????????
        intentFilter.addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED);

        appContext.registerReceiver(
            // No lambda possible here.
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, @NonNull Intent i) {
                    //??????????????????Log.w("AESOP " + getClass().getSimpleName(), "BCR for " + lastDownload);
                    // Note the capture of lastDownload here. That's critical to the
                    // design to keep it from being confused by "old" downloads queued
                    // up by the download manager.

                    if (i.getAction() == null) {
                        // This can get called for "ancient" (long since abandoned) requests
                        return;
                    }

                    long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                    //Checking if the received broadcast is for our enqueued download
                    if (lastDownload == id) {
                        switch (i.getAction()) {
                            case DownloadManager.ACTION_NOTIFICATION_CLICKED:
                                break;
                            case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                                appContext.unregisterReceiver(this);
                                downloadUsingManager_end(lastDownload);
                                break;
                        }
                    }
                    else {
                        Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(id));
                        cursor.moveToFirst();
                        /* ?????????????????
                        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                        int bytes = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int soFar = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        Log.w("AESOP " + getClass().getSimpleName(), "Alternate BCR " + status + " " + reason + " " + bytes + " " + soFar);
                         */
                    }
                    // otherwise, just ignore it -- it's a leftover the manager is finally
                    // telling us about.
                }
            }, intentFilter);

        retriesDone = 0;
        cancelsDone = 0;
        checkDownloadProgress(requested, newTitle, lastDownload);
    }

    @WorkerThread
    void downloadUsingManager_end(long lastDownload) {
        Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(lastDownload));
        if (cursor == null) {
            logActivityIndented("Error: download: no cursor (internal error)");
        }
        else {
            if (cursor.moveToFirst()) {
                int internalId = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_ID));
                if (internalId == lastDownload) {
                    int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    String fileLocalUriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                    String fileUriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI));

                    switch (status) {
                        case DownloadManager.STATUS_SUCCESSFUL: {
                            downloadedString = fileLocalUriString;
                            downloadCompletes.resume();
                            break;
                        }

                        case DownloadManager.STATUS_PAUSED:
                        case DownloadManager.STATUS_FAILED: {
                            int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                            String reasonText;
                            switch (reason) {
                                case DownloadManager.ERROR_CANNOT_RESUME:
                                    reasonText = "ERROR_CANNOT_RESUME";
                                    break;
                                case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                                    reasonText = "ERROR_DEVICE_NOT_FOUND";
                                    break;
                                case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                                    reasonText = "ERROR_FILE_ALREADY_EXISTS";
                                    break;
                                case DownloadManager.ERROR_FILE_ERROR:
                                    reasonText = "ERROR_FILE_ERROR";
                                    break;
                                case DownloadManager.ERROR_HTTP_DATA_ERROR:
                                    reasonText = "ERROR_HTTP_DATA_ERROR";
                                    break;
                                case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                                    reasonText = "ERROR_INSUFFICIENT_SPACE";
                                    break;
                                case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                                    reasonText = "ERROR_TOO_MANY_REDIRECTS";
                                    break;
                                case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                                    reasonText = "ERROR_UNHANDLED_HTTP_CODE";
                                    break;
                                case DownloadManager.ERROR_UNKNOWN:
                                    reasonText = "ERROR_UNKNOWN";
                                    break;
                                case DownloadManager.PAUSED_WAITING_TO_RETRY:
                                    reasonText = "Too long without progress . Bad host id?";
                                    break;
                                case 404:
                                    reasonText = "File not found on host";
                                    break;
                                default:
                                    reasonText = "Failure Code: " + reason;
                                    break;
                            }

                            logActivityIndented("Error: download of " + fileUriString + " failed with download error: " + reasonText);
                            downloadedString = null;
                            downloadCompletes.resume();

                            // Remove failures.  (This removes files, so not for successes.)
                            downloadManager.remove(lastDownload);
                            break;
                        }
                        default:
                            // nothing: ignore (not for us)
                            break;
                    }
                }
            }
            cursor.close();
        }
    }

    final int RETRIES_MAX = 5;
    final int CANCELS_MAX = 5;
    int retriesDone;
    int cancelsDone;
    enum Actions { ACTION_DONE, ACTION_CONTINUE, ACTION_RETRY, ACTION_CANCEL}
    final long POLL_INTERVAL = 5000; // Millis

    private void checkDownloadProgress(
            // The "unused" below are false positives from the editor (the analyzer is happy).
            @SuppressWarnings("unused") final String requested,
            @SuppressWarnings("unused") final String newTitle,
            final long downloadId) {

        // Note captured downloadId below. As above it's necessary to avoid working in the wrong things
        handler.postDelayed(() -> {
            //????????????????????? Log.w("AESOP " + getClass().getSimpleName(), "Got post for " + downloadId);
            final DownloadManager.Query query = new DownloadManager.Query();
            // Filter only by ID: adding other filters can (does?) yield an "or" query, and
            // the moveToFirst() won't get our ID.
            query.setFilterById(downloadId);
            final Cursor cursor = downloadManager.query(query);
            if (cursor.moveToFirst()) {
                int internalId = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_ID));
                if (internalId != downloadId) {
                    // In spite of the filter this can happen...
                    return;
                }
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                Actions action = Actions.ACTION_DONE;

                int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                switch (status) {
                    case DownloadManager.STATUS_PAUSED: {
                        int bytes = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int soFar = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        //???? Log.w("AESOP " + getClass().getSimpleName(), "Paused P/P " + status + " " + reason + " " + bytes + " " + soFar);
                        if (soFar > 0) {
                            //???? Log.w("AESOP " + getClass().getSimpleName(), "Retry on bad pause");
                            action = Actions.ACTION_RETRY;
                            break;
                        }
                        // drop thru
                    }
                    case DownloadManager.STATUS_PENDING: {
                        int bytes = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int soFar = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        //?????????? Log.w("AESOP " + getClass().getSimpleName(), "P/P " + status + " " + reason + " " + bytes + " " + soFar);
                        // For STATUS_PENDING:
                        // This is "startup". It's possible that the start is very slow
                        // setting up the connection, so we give it an extra chance.

                        // For STATUS_PAUSED:
                        // None of the sub-codes seem to have any useful action but to ultimately
                        // cancel if the download manager doesn't figure it out.
                        action = Actions.ACTION_CANCEL;
                        break;
                    }

                    case DownloadManager.STATUS_RUNNING: {
                        //?????????? Log.w("AESOP " + getClass().getSimpleName(), "R " + status);
                        action = Actions.ACTION_CONTINUE;
                        break;
                    }

                    case DownloadManager.STATUS_FAILED:
                    case DownloadManager.STATUS_SUCCESSFUL: {
                        //?????????? Log.w("AESOP " + getClass().getSimpleName(), "F/S " + status + " " + reason);
                        // For STATUS_SUCCESSFUL:
                        // nothing more to do here.
                        // For STATUS_FAILED
                        // If the download manager thinks it's hopeless, we can only agree.
                        action = Actions.ACTION_DONE;
                        break;
                    }
                }

                switch (action) {
                case ACTION_DONE: {
                    downloadUsingManager_end(downloadId);
                    break;
                }

                case ACTION_RETRY: {
                    if (retriesDone >= RETRIES_MAX) {
                        // Failure... give up
                        //?????????? Log.w("AESOP " + getClass().getSimpleName(), "declare timeout, removing ");
                        downloadUsingManager_end(downloadId);
                    }
                    else {
                        retriesDone++;
                        downloadManager.remove(downloadId);
                        // Restart the request (cancel the prior one)
                        //?????????? Log.w("AESOP " + getClass().getSimpleName(), "do retry ");
                        downloadUsingManager_start(requested, newTitle);
                    }
                    break;
                }

                case ACTION_CONTINUE: {
                    retriesDone = 0; // Some progress has been made... make full retries available
                    cancelsDone = 0;
                    checkDownloadProgress(requested, newTitle, downloadId);
                    break;
                }

                case ACTION_CANCEL: {
                    if (cancelsDone >= CANCELS_MAX) {
                        downloadUsingManager_end(downloadId);
                    }
                    else {
                        cancelsDone++;
                        checkDownloadProgress(requested, newTitle, downloadId);
                    }
                    break;
                }
                }
            }
            // Ignore a call that doesn't match what we're expecting
            cursor.close();
        }, POLL_INTERVAL);
    }

    @WorkerThread
    private boolean install(@NonNull String fileUrl, String title, boolean priorDownload) {
        File fileToInstall;
        // Install the book named by the "file:" fileUrl
        // We allow the otherwise illegal file:<relative pathname> (no slash after :) to mean
        // an Android sdcard-local filename.
        Asserts.checkState(fileUrl.substring(0,5).equals("file:"));
        if (fileUrl.charAt(5) != '/') {
            String pathName = fileUrl.substring(5);
            // a relative path - find relative to downloadDir's parent
            fileToInstall = new File(downloadDir.getParent(), pathName);
        }
        else {
            fileToInstall = new File(fileUrl.substring(7));
        }

        if (!fileToInstall.exists()) {
            logActivityIndented( "File not found: " + fileToInstall.getPath());
            return false;
        }

        if (title == null) {
           title = AudioBook.filenameCleanup(fileToInstall.getName());
        }

        File audioFile;
        if (isAudioPath(fileToInstall.getName())) {
            audioFile = fileToInstall;
        }
        else {
            audioFile = FileUtilities.findFileMatching(fileToInstall, FilesystemUtil::isAudioPath);
            if (audioFile == null) {
                logActivityIndented( "Not an audiobook: no audio files found: " + fileToInstall.getPath());
                if (FileUtilities.deleteTree(fileToInstall, this::logResult)) {
                    logActivityIndented("Deleted " + fileToInstall.getPath());
                }
                postResults(fileToInstall.getPath());
                return false;
            }
        }

        Provisioning.Candidate candidate = new Provisioning.Candidate();
        candidate.fill(title, fileToInstall.getPath(), audioFile);
        candidate.isSelected = true;
        candidate.computeDisplayTitle();

        bookListChanging(true);
        boolean r = retainBooks || !Objects.requireNonNull(fileToInstall.getParentFile()).canWrite();
        provisioning.moveOneFile_Task(candidate, this::installProgress, r, renameFiles);

        if (postResults(fileToInstall.getPath()) && priorDownload) {
            logActivityIndented("BE SURE to delete or finish installing this failed install." );
        }
        bookListChanged();

        return true;
    }

    @SuppressLint("DefaultLocale")
    @WorkerThread
    private void booksCommands(@NonNull List<String> operands) {
        String key = operands.get(0).toLowerCase();
        switch (key) {
        case "books:books": {
            if (errorIfAnyRemaining(operands)) {
                return;
            }

            buildBookList();
            provisioning.selectCompletedBooks();

            logActivityIndented("Current Books  " + provisioning.getTotalTimeSubtitle());
            logActivityIndented(" Length   Current        C W Title");
            Provisioning.BookInfo[] sortedList = provisioning.bookList.clone();
            Arrays.sort(sortedList, (l,r)->{
                // Sort on path names (not display titles)
                String lName = l.book.getPath().getPath();
                String rName = r.book.getPath().getPath();
                return lName.compareTo(rName);
            });
            String dirPath = "";
            for (Provisioning.BookInfo bookInfo : sortedList) {
               AudioBook book = bookInfo.book;
               String dir = book.getPath().getParent();
                assert dir != null;
                if (!dir.equals(dirPath)) {
                   dirPath = dir;
                   logActivityIndented("In " + dir);
               }
               String line = String.format("%1s%-8s %-14s %1s %1s %s",
                   bookInfo.current ? ">" : " ",
                   UiUtil.formatDuration(book.getTotalDurationMs()),
                   book.thisBookProgress(appContext),
                   book.getCompleted() ? "C" : " ",
                   bookInfo.unWritable ? "N" : " ",
                   book.getDisplayTitle());
               if (!book.getDirectoryName().equals(book.getDisplayTitle())) {
                   line += " -> " + book.getDirectoryName();
               }
               if (book.duplicateIdCounter == 1) {
                   line = "     " + line;
               }
               else {
                   line = String.format("  %2d ",book.duplicateIdCounter) + line;
               }
               logActivity(line); // not indented... we already did that
            }
            logActivityIndented("");
            break;
        }
        case "books:clean": {
            buildBookList();
            provisioning.selectCompletedBooks();
            if (checkOperandsFor(operands, "all")) {
                for (Provisioning.BookInfo b : provisioning.bookList) {
                    if (!b.current) {
                        b.selected = true;
                    }
                }
            }

            if (checkOperandsFor(operands, "current")) {
                for (Provisioning.BookInfo b : provisioning.bookList) {
                    if (b.current) {
                        b.selected = true;
                    }
                }
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            logActivityIndented("Cleaned books (if any found):");
            runDelete();
            break;
        }
        case "books:delete": {
            ArrayList<String> partialTitles = new ArrayList<>();
            String current;
            while ((current = findOperandString(operands)) != null) {
                partialTitles.add(current);
            }
            if (errorIfAnyRemaining(operands)) {
                return;
            }
            if (partialTitles.isEmpty()) {
                logActivityIndented("No partial titles found to delete");
                return;
            }

            buildBookList();
            boolean OK = true;
            for (String partialTitle: partialTitles) {
                if (findInBookList(partialTitle) == null) {
                    logActivityIndented("No unique match found for "+ partialTitle);
                    OK = false;
                    // Policy: either they all match partial titles, or do nothing.
                }
            }
            if (!OK) {
                // Policy: either they all match partial titles, or do nothing.
                return;
            }

            runDelete();
            break;
        }
        case "books:rename": {
            String partialTitle = findOperandString(operands);
            String newTitle = findOperandString(operands);

            if (partialTitle == null || partialTitle.isEmpty() || newTitle == null) {
                logActivityIndented("Requires exactly two string operands");
                return;
            }

            newTitle = checkName(newTitle);
            if (newTitle == null) {
                return;
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            buildBookList();
            Provisioning.BookInfo bookInfo = findInBookList(partialTitle);
            if (bookInfo == null) {
                logActivityIndented("No unique match found for '"+ partialTitle + "'");
                return;
            }

            logActivityIndented("Rename " + bookInfo.book.getPath().getPath() + " to '" + newTitle + "'");

            bookListChanging(true);
            bookInfo.book.renameTo(newTitle, this::logResult);
            postResults(bookInfo.book.getPath().getPath());
            bookListChanged();
            return;
        }
        case "books:reset": {
            boolean all = checkOperandsFor(operands, "all");
            String newTimeString = findOperandText(operands);
            ArrayList<String> partialTitles = new ArrayList<>();
            String current;
            while ((current = findOperandString(operands)) != null) {
                partialTitles.add(current);
            }

            if (all != partialTitles.isEmpty()) {
                logActivityIndented("Requires either 'all' or a list of partial titles.");
                return;
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            long newTime = 0;
            if (newTimeString != null) {
                newTime = AudioBook.timeToMillis(newTimeString);
            }
            if (newTime < 0) {
                logActivityIndented("Cannot interpret time: " + newTimeString);
                return;
            }

            buildBookList();

            if (all) {
                bookListChanging(true);
                for (Provisioning.BookInfo bookInfo : provisioning.bookList) {
                    bookInfo.book.setNewTime(newTime);
                    logActivityIndented("Position in "+ bookInfo.book.getDisplayTitle() +
                            " changed to " + UiUtil.formatDurationShort(newTime));
                }
            }
            else {
                // one or more partial titles
                boolean OK = true;
                for (String partialTitle: partialTitles) {
                    Provisioning.BookInfo bookInfo = findInBookList(partialTitle);
                    if (bookInfo == null) {
                        logActivityIndented("No unique match found for " + partialTitle);
                        OK = false;
                    }
                }
                if (!OK) {
                    // Policy: all recognized or nothing
                    return;
                }
                bookListChanging(true);
                for (String partialTitle: partialTitles) {
                    Provisioning.BookInfo bookInfo = findInBookList(partialTitle);
                    if (bookInfo != null) {
                        bookInfo.book.setNewTime(newTime);
                        logActivityIndented("Position in "+ bookInfo.book.getDisplayTitle() +
                                " changed to " + UiUtil.formatDurationShort(newTime));
                    }
                }
            }
            bookListChanged();
            postResults(null);

            break;
        }
        default:
            logActivityIndented("Unrecognized request ");
            break;
        }
    }

    @WorkerThread
    private void runDelete() {
        bookListChanging(true);
        provisioning.deleteAllSelected_Task(this::installProgress, archiveBooks);
        postResults(null);
        bookListChanged();
    }

    @WorkerThread
    private void downloadsCommands(@NonNull List<String> operands) {
        String key = operands.get(0).toLowerCase();
        switch (key) {
        case "downloads:books": {
            buildCandidateList();
            if (errorIfAnyRemaining(operands)) {
                return;
            }

            logActivityIndented("Current Downloaded books in " + currentCandidateDir.getPath());
            logActivityIndented("C Name [-> Title]");
            for (Provisioning.Candidate candidate : provisioning.candidates) {
                String dirName = new File(candidate.oldDirPath).getName();
                String line = String.format("%1s %s",
                        candidate.collides ? "C" : " ",
                        dirName);
                if (candidate.bookTitle != null) {
                    if (!candidate.bookTitle.equals(dirName)) {
                        line += " -> " + candidate.bookTitle;
                    }
                }
                logActivityIndented(line);
            }
            logActivityIndented("");
            break;
        }
        case "downloads:directory": {
            String dirName = findOperandString(operands);
            if (dirName == null) {
                logActivityIndented( "Directory name required");
                return;
            }
            if (errorIfAnyRemaining(operands)) {
                return;
            }
            File newDir;
            if (dirName.charAt(0) == '/') {
                newDir = new File(dirName);
            }
            else {
                newDir = new File(downloadDir.getParent(), dirName);
            }
            if (!newDir.exists()) {
                logActivityIndented("New download directory " + newDir.getPath() + " not found");
                return;
            }
            logActivityIndented("Download directory set to " + newDir.getPath());
            currentCandidateDir = newDir;

            candidatesIsAudioBooks = false;
            final List<File> audioBooksDirs = FilesystemUtil.audioBooksDirs(getAppContext());
            for (File activeStorage : audioBooksDirs) {
                if (currentCandidateDir.equals(activeStorage)) {
                    candidatesIsAudioBooks = true;
                    break;
                }
            }
            break;
        }
        case "downloads:install": {
            String partialTitle;
            String newTitle;

            boolean all = checkOperandsFor(operands, "all");
            if (all) {
                partialTitle = null;
                newTitle = null;
            }
            else {
                partialTitle = findOperandString(operands);
                newTitle = findOperandString(operands);
                if (partialTitle == null) {
                    logActivityIndented("'All' or a partial title required");
                    return;
                }
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            if (newTitle != null) {
                newTitle = checkName(newTitle);
                if (newTitle == null) {
                    return;
                }
            }

            buildCandidateList();
            if (partialTitle != null) {
                Provisioning.Candidate candidate = findInCandidatesList(partialTitle);
                if (candidate == null) {
                    logActivityIndented("No unique match found for " + partialTitle);
                    return;
                }
                // check for collisions elsewhere
                candidate.isSelected = true;
                if (newTitle != null) {
                    candidate.newDirName = newTitle;
                }
                candidate.collides = false;
            } else {
                // must be 'all'
                for (Provisioning.Candidate candidate : provisioning.candidates) {
                    candidate.isSelected = true;
                }
            }

            bookListChanging(true); // this op's effects are to the book list!
            boolean r = retainBooks || !currentCandidateDir.canWrite();
            provisioning.moveAllSelected_Task(this::installProgress, r, renameFiles);
            postResults(null);
            bookListChanged();

            break;
        }
        case "downloads:delete": {
            boolean all = checkOperandsFor(operands, "all");
            ArrayList<String> partialTitles = new ArrayList<>();
            String current;
            while ((current = findOperandString(operands)) != null) {
                partialTitles.add(current);
            }

            if (all != partialTitles.isEmpty()) {
                logActivityIndented("Requires either 'all' or a list of partial titles.");
                return;
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            buildCandidateList();

            if (all) {
                bookListChanging(false);
                for (Provisioning.Candidate candidate : provisioning.candidates) {
                    if (FileUtilities.deleteTree(new File(candidate.oldDirPath), this::logResult)) {
                        logActivityIndented("Deleted " + candidate.oldDirPath);
                    }
                }
            } else {
                // one or more partial titles
                boolean OK = true;
                for (String partialTitle: partialTitles) {
                    Provisioning.Candidate candidate = findInCandidatesList(partialTitle);
                    if (candidate == null) {
                        logActivityIndented("No unique match found for " + partialTitle);
                        OK = false;
                    }
                }
                if (!OK) {
                    // Policy: all recognized or nothing
                    return;
                }
                bookListChanging(false);
                for (String partialTitle: partialTitles) {
                    Provisioning.Candidate candidate = findInCandidatesList(partialTitle);
                    if (candidate != null) {
                        if (FileUtilities.deleteTree(new File(candidate.oldDirPath), this::logResult)) {
                            logActivityIndented("Deleted " + candidate.oldDirPath);
                        }
                    }
                }
            }
            bookListChanged();
            postResults(null);

            break;
        }
        case "downloads:group": {
            String target = findOperandString(operands);
            if (target == null) {
                logActivityIndented("A new book name is required");
                return;
            }

            buildCandidateList();

            String partialTitle;
            int candidates = 0;
            Provisioning.Candidate firstCandidate = null;
            while((partialTitle = findOperandString(operands)) != null) {
                Provisioning.Candidate candidate = findInCandidatesList(partialTitle);
                if (candidate == null) {
                    logActivityIndented("No unique match found for " + partialTitle);
                    candidates = -10000;
                }
                if (firstCandidate == null) {
                    firstCandidate  = candidate;
                }
                candidates++;
            }
            if (candidates < 0) {
                // This so we check them all for matches
                return;
            }
            if (candidates == 0) {
                logActivityIndented("No books found to combine.");
                return;
            }
            if (errorIfAnyRemaining(operands)) {
                return;
            }
            if (target.indexOf('/') >= 0) {
                logActivityIndented("New book name must be a name, not a path");
                return;
            }

            target = checkName(target);
            if (target == null) {
                return;
            }
            assert(firstCandidate != null);

            // The parent plus the new name -> targetFile
            File targetFile = new File(firstCandidate.oldDirPath).getParentFile();
            targetFile = new File(targetFile, target);
            if (!targetFile.mkdirs()) {
                logActivityIndented("Could not make book directory.");
                return;
            }

            bookListChanging(false);
            logActivityIndented("Grouping " + candidates + " books into " + target);
            provisioning.groupAllSelected_execute(targetFile);
            postResults(null);

            // Update the candidate list since we know it changed.
            // And just in case we were in AudioBooks, the bookList too.
            bookListChanged();
            buildCandidateList();

            break;
        }
        case "downloads:ungroup": {
            String partialTitle = findOperandString(operands);
            if (partialTitle == null) {
                logActivityIndented("An existing book name is required");
                return;
            }

            if (errorIfAnyRemaining(operands)) {
                return;
            }

            buildCandidateList();
            bookListChanging(false);
            Provisioning.Candidate groupDir = findInCandidatesList(partialTitle);
            if (groupDir == null) {
                logActivityIndented("Book to ungroup not uniquely matched.");
                return;
            }
            logActivityIndented("Ungrouping " + groupDir.newDirName);
            provisioning.unGroupSelected_execute();
            postResults(null);
            bookListChanged();

            // Update the candidate list since we know it changed.
            // And just in case we were in AudioBooks, the bookList too.
            buildCandidateList();

            break;
        }
        case "downloads:rawfiles": {
            final String[] files = currentCandidateDir.list();
            if (files == null) {
                logActivityIndented("Directory is empty");
                return;
            }

            Arrays.sort(files, String::compareTo);
            final int[] lengths = new int[(files.length)];

            int max = 0;
            for (int i=0; i<files.length; i++) {
               int len = files[i].length() + StringUtils.countMatches(files[i], '"');
               max = Math.max(len,max);
               lengths[i] = len;
            }

            final int columnWidth = 80;
            final int gutterWidth = 1;
            final int remaining = columnWidth - max;

            // A preliminary number of columns
            int nColumns = (remaining/(gutterWidth + 2)) + 1;  // assume a gutter width, and 2 character filenames.
            int nRows = files.length;
            int[] widthList = new int[nColumns];
            while (nColumns > 1) {
                nRows = (files.length+nColumns)/nColumns;

                for (int col = 0; col < nColumns; col++) {
                    int maxLen = 0; // the longest item in the current column
                    for (int row = 0; row < nRows; row++) {
                        int item = col * nRows + row;
                        if (item >= lengths.length) {
                            break;
                        }
                        maxLen = Math.max(maxLen, lengths[item]);
                    }
                    widthList[col] = maxLen;
                }

                int lengthSum = 0;
                for (int i=0; i<nColumns; i++) {
                    lengthSum += widthList[i];
                }

                if (lengthSum + (nColumns-1)*gutterWidth <= columnWidth) {
                    // It fits, we have the layout
                    break;
                }
                // it won't fit
                nColumns--;
            }

            final StringBuilder text = new StringBuilder(columnWidth+1);
            final String emptySpace = StringUtils.repeat(' ', columnWidth/2);
            for (int row = 0; row < nRows; row++) {
                for (int col = 0; col < nColumns; col++) {
                    int item = col * nRows + row;
                    if (item >= lengths.length) {
                        break;
                    }
                    String fileName = files[item];
                    if (fileName.indexOf('"') >= 0) {
                        // it contains the quotes we allowed for above
                        fileName = fileName.replaceAll("\"", "\\\\\"");
                    }
                    text.append('"');
                    text.append(fileName);
                    text.append('"');
                    int spaces = widthList[col] - lengths[item] + gutterWidth;
                    text.append(emptySpace, 0, spaces);
                }
                logActivityIndented(text.toString());
                text.setLength(0);
            }

            break;
        }
        case "downloads:rawdelete": {
            ArrayList<String> files = new ArrayList<>();
            String current;
            while ((current = findOperandString(operands)) != null) {
                files.add(current);
            }
            if (errorIfAnyRemaining(operands)) {
                return;
            }
            if (files.isEmpty()) {
                logActivityIndented("No file names provided to delete");
                return;
            }

            bookListChanging(false);
            for (String fileName: files) {
                File toDelete = new File(currentCandidateDir, fileName);
                if (!toDelete.exists()) {
                    logActivityIndented("File " + toDelete.getPath() + " already deleted.");
                }
                else {
                    logActivityIndented("Deleting File " + toDelete.getPath());
                    if (!FileUtilities.deleteTree(toDelete, this::logResult)) {
                        postResults(fileName);
                    }
                }
            }
            bookListChanged();

            break;
        }
        default: {
            logActivityIndented("Unrecognized request ");
            break;
        }
        }
    }

    @WorkerThread
    private void settingsCommands(@NonNull List<String> operands) {
        String key = operands.get(0).toLowerCase();
        switch (key) {
            case "settings:archive": {
                String r = booleanOperand(operands);
                if (!r.equals("error")) {
                    archiveBooks = r.equals("true");
                }
                logActivityIndented("Deleted Books will " + (archiveBooks?"":"not ") + "be archived.");
                break;
            }
            case "settings:retain": {
                String r = booleanOperand(operands);
                if (!r.equals("error")) {
                    retainBooks = r.equals("true");
                }
                logActivityIndented("Installed books will " + (retainBooks?"":"not ") + "be retained.");
                break;
            }
            case "settings:rename": {
                String r = booleanOperand(operands);
                if (!r.equals("error")) {
                    renameFiles = r.equals("true");
                }
                logActivityIndented("Audio filenames will " + (renameFiles?"":"not ") + "be renumbered.");
                break;
            }
            case "settings:mobiledata": {
                String r = booleanOperand(operands);
                if (!r.equals("error")) {
                    allowMobileData = r.equals("true");
                }
                logActivityIndented("Download using Mobile Phone network " + (allowMobileData ?"":"not ") + "permitted.");
                break;
            }
            case "settings:manager": {
                String r = booleanOperand(operands);
                if (!r.equals("error")) {
                    // Setting it twice forces managed https downloads on Android 4
                    boolean b = r.equals("true");
                    forceDownloadManager = useDownloadManager && b;
                    useDownloadManager = b;
                }
                if (useDownloadManager) {
                    logActivityIndented("Downloads will be performed using the Download Manager");
                    downloadManager = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
                }
                else {
                    logActivityIndented("Downloads will be performed using sockets");
                }
                break;
            }
            default: {
                logActivityIndented("Unrecognized request ");
                break;
            }
        }
    }

    @WorkerThread
    private void runCommands(@NonNull List<String> operands) {
        // returns true when the script should be run
        // All times in device local time
        String key = operands.get(0).toLowerCase();
        switch (key) {
            case "run:at": {
                if (lineCounter != 1) {
                    logActivityIndented("run:at must be the first command");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

                String timeString = findOperandText(operands);
                if (timeString == null) {
                    logActivityIndented("run:at requires time-of-day operand(s)");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

                String more = findOperandText(operands);
                if (more != null) {
                    timeString += more;
                }

                if (errorIfAnyRemaining(operands)) {
                    logActivityIndented("Too many operands for run:at");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

                Calendar scheduledStartTime = Calendar.getInstance();

                Calendar requestedHHMM = parseTimeOfDay(timeString);
                if (requestedHHMM == null) {
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

                int hour = requestedHHMM.get(Calendar.HOUR_OF_DAY);
                int minute = requestedHHMM.get(Calendar.MINUTE);
                scheduledStartTime.set(Calendar.HOUR_OF_DAY, hour);
                scheduledStartTime.set(Calendar.MINUTE, minute);
                scheduledStartTime.set(Calendar.SECOND, 0);
                if (scheduledStartTime.before(messageSentTime)) {
                    scheduledStartTime.add(Calendar.DAY_OF_YEAR, 1);
                }

                if (processingStartTime.before(scheduledStartTime)) {
                    logActivityIndented("Skipping run until scheduled time of " + scheduledStartTime.getTime());
                    setEarliestSavedAtTime(scheduledStartTime);
                    globalSettings.setSavedControlFileTimestamp(scheduledStartTime.getTimeInMillis()-1);
                    continueProcessing = false;
                    generateReport = false;
                    deleteMessage = false;
                    return;
                }

                logActivityIndented("Delayed Request scheduled for " + scheduledStartTime.getTime() + " runs.");
                return;
            }
            case "run:every": {
                if (lineCounter != 1) {
                    logActivityIndented("run:every must be the first command");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }
                int todayDayName = processingStartTime.get(Calendar.DAY_OF_WEEK);
                DateFormatSymbols dfs = new DateFormatSymbols(Locale.US);
                String[] weekdays = dfs.getWeekdays();

                boolean matchedToday = false;
                for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
                    if (checkOperandsFor(operands, weekdays[d])) {
                        matchedToday |= d == todayDayName;
                    }
                    if (checkOperandsFor(operands, weekdays[d].substring(0,3))) {
                        matchedToday |= d == todayDayName;
                    }
                }

                matchedToday |= checkOperandsFor(operands, "everyday");
                matchedToday |= checkOperandsFor(operands, "every");

                if (checkOperandsFor(operands, "weekday")
                    || checkOperandsFor(operands, "week")) {
                    matchedToday |= (todayDayName >= Calendar.MONDAY && todayDayName <= Calendar.FRIDAY);
                }

                String timeString = findOperandText(operands);

                if (errorIfAnyRemaining(operands)) {
                    logActivityIndented("Unrecognized operands for run:every");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

                Calendar scheduledStartTime = Calendar.getInstance();
                //noinspection IfStatementWithIdenticalBranches
                if (timeString != null) {

                    Calendar requestedHHMM = parseTimeOfDay(timeString);
                    if (requestedHHMM == null) {
                        continueProcessing = false;
                        deleteMessage = true;
                        return;
                    }

                    int hour = requestedHHMM.get(Calendar.HOUR_OF_DAY);
                    int minute = requestedHHMM.get(Calendar.MINUTE);
                    scheduledStartTime.set(Calendar.HOUR_OF_DAY, hour);
                    scheduledStartTime.set(Calendar.MINUTE, minute);
                    scheduledStartTime.set(Calendar.SECOND, 0);
                }
                else {
                    // No time parameter, default to 0300
                    scheduledStartTime.set(Calendar.HOUR_OF_DAY, 3);
                    scheduledStartTime.set(Calendar.MINUTE, 0);
                    scheduledStartTime.set(Calendar.SECOND, 0);
                }

                // It's valid, and thus actually runnable. Don't delete.
                deleteMessage = false;

                if (!matchedToday) {
                    // Not today
                    continueProcessing = false;
                    generateReport = false;

                    // Set it to 00:01 tomorrow, and see when/if it should actually run then.
                    scheduledStartTime.set(Calendar.MINUTE, 1);
                    scheduledStartTime.set(Calendar.HOUR_OF_DAY, 24);
                    setEarliestSavedAtTime(scheduledStartTime);
                    // (No log here! -- one isn't even generated)
                    return;
                }

                if (processingStartTime.before(scheduledStartTime)) {
                    // Later today
                    continueProcessing = false;
                    generateReport = false;
                    setEarliestSavedAtTime(scheduledStartTime);
                    if (consoleLog) {
                        Log.v("AESOP " + getClass().getSimpleName() + " " + Thread.currentThread().getId(),
                                "Run at:every -- run later today at " + scheduledStartTime.getTime() + ".");
                    }
                    return;
                }

                // Earlier today (now!)
                logActivityIndented("Run at:every -- Starts at " + scheduledStartTime.getTime() + ".");

                scheduledStartTime.set(Calendar.MINUTE, 1);
                scheduledStartTime.set(Calendar.HOUR_OF_DAY, 24);
                setEarliestSavedAtTime(scheduledStartTime);
                return;
            }
            default: {
                logActivityIndented("Unrecognized request ");
                break;
            }
        }
    }

    Calendar parseTimeOfDay (String timeString) {
        Date result = null;

        DateFormat formatter = new SimpleDateFormat("hh:mma", Locale.US);
        try {
            result = formatter.parse(timeString);
        } catch (ParseException e) {
            // ignore
        }

        if (result == null) {
            if (timeString.matches("\\d\\d\\d\\d")) {
                formatter = new SimpleDateFormat("HHmm", Locale.US);
                try {
                    result = formatter.parse(timeString);
                } catch (ParseException e) {
                    // ignore
                }
            }
        }

        if (result == null) {
            logActivityIndented("Time not parsed: must be either HH:mm [AM|PM] (12 hour time) or HHMM (24 hour time)");
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(result);

        return cal;
    }

    @WorkerThread
    void buildCandidateList() {
        provisioning.candidates.clear();
        provisioning.buildCandidateList_Task(currentCandidateDir,this::installProgress);
        postResults(null);
        // The below makes it synchronous
        provisioning.joinCandidatesSubTasks();
    }

    @WorkerThread
    void buildBookList() {
        UiControllerBookList.suppressAnnounce();
        booksPreUseUpdateComplete.prepare(); // Use completion event to know it's finished updating
                                     // This is vital on first use.
        EventBus.getDefault().post(new MediaStoreUpdateEvent());
        booksPreUseUpdateComplete.await();

        // Wait until any pending duration queries complete
        awaitDurationQueries();
        provisioning.buildBookList();
        UiControllerBookList.resumeAnnounce();
    }

    @WorkerThread
    void installProgress(@NonNull Provisioning.ProgressKind kind, String string) {
        switch (kind) {
            case SEND_TOAST:
                // Ignore
                break;
            case FILESYSTEMS_FULL:
                // Occurs only on book install.
                // An error was posted just before this error is reported.
                // That error will show up in postResults, where the user will see it,
                // along with a further warning to clean up.
                // Nothing to do here.
                break;
            case BOOK_DONE:
            case ALL_DONE:
                break;
        }
    }

    @WorkerThread
    private boolean postResults(String targetFile) {
        boolean errorsFound = false;
        for (Provisioning.ErrorInfo e : provisioning.errorLogs) {
            switch (e.severity) {
            case INFO:
                logActivityIndented(e.text);
                break;
            case MILD:
            case SEVERE:
                logActivityIndented(e.severity + " " + e.text);
                if (targetFile != null) {
                    logActivityIndented("  ... for file " + targetFile);
                }
                errorsFound = true;
                break;
            }
        }

        provisioning.clearErrors();
        return errorsFound;
    }

    @WorkerThread
    private void logResult(Provisioning.Severity severity, String text) {
        logActivityIndented(severity + " " + text);
    }

    @WorkerThread
    private void logActivity(String s) {
        if (consoleLog) {
            Log.v("AESOP " + getClass().getSimpleName() + " " + Thread.currentThread().getId(), ">>>>log " + s);
        }
        singleRequestResultLog.add(s);
    }

    @WorkerThread
    private void logActivityIndented(String s) {
        if (consoleLog) {
            Log.v("AESOP " + getClass().getSimpleName() + " " + Thread.currentThread().getId(), ">>>>log      " + s);
        }
        singleRequestResultLog.add("     " + s);
    }

    @WorkerThread
    private void startReport() {
        // Certain things (like run:at and run:every) can set generateReport false;
        generateReport = true;
        singleRequestResultLog.clear();
    }

    @WorkerThread
    private boolean endReport() {
        if (generateReport) {
            logActivity("End of request " + getDeviceTag() + " at " + Calendar.getInstance().getTime());
            perRequestReport();
            return true;
        }
        return false;
    }

    @WorkerThread
    private void perRequestReport() {
        // Emit the results from this request into the final report.
        // Requests which do nothing due to a run:at or run:every don't
        // actually get emitted (this function isn't called).

        if (consoleLogReport) {
            Log.v("AESOP " + getClass().getSimpleName(), "**********************************");
            for (String s : singleRequestResultLog) {
                Log.v("AESOP " + getClass().getSimpleName(), "Log: " + s);
            }
            Log.v("AESOP " + getClass().getSimpleName(), "**********************************");
        }

        compositeResultLog.addAll(singleRequestResultLog);
    }

    @SuppressLint("UsableSpace")
    @WorkerThread
    private void sendFinalReport() {
        // Send the report of what happened. Write it locally to a file (next to the control file)
        // and mail it (if authorized). Always do both just in case the mail fails.
        // If several requests are processed in the same cycle, this handles the group.

        // see if perRequestReport() was called at least once
        if (compositeResultLog.isEmpty()) {
            return;
        }

        // Tell the user about space remaining
        final List<File>audioBooksDirs = FilesystemUtil.audioBooksDirs(getAppContext());
        for (File activeStorage : audioBooksDirs) {
            long remainingSpace = activeStorage.getTotalSpace() - activeStorage.getUsableSpace();
            logActivity("Space on " + activeStorage.getParent() + ": Using " +
                    remainingSpace/1000000 + "Mb of " +
                    activeStorage.getTotalSpace()/1000000 + "Mb (" +
                    (int)(((float)remainingSpace/
                            (float)activeStorage.getTotalSpace())*100) + "%)");
        }

        compositeResultLog.addAll(singleRequestResultLog);

        File resultFile = new File(controlDir, resultFileName);
        try {
            FileUtils.writeLines(resultFile, compositeResultLog);
        } catch (Exception e) {
            CrashWrapper.recordException(TAG, e);
        }

        if (sendResultTo.size() > 0) {
            Mail message = new Mail()
                    .setSubject("Aesop results from request " + getDeviceTag());
            // Add a trailing empty line so the join below ends with a newline
            compositeResultLog.add("");
            message.setMessageBody(TextUtils.join("\n", compositeResultLog));
            for (String r:sendResultTo) {
                message.setRecipient(r);
            }
            message.sendEmail();
        }

        compositeResultLog.clear();
    }

    @NonNull
    private String getDeviceTag() {
        String name = globalSettings.getMailDeviceName();
        if (name == null) {
            name = "";
        }
        if (!name.isEmpty()) {
            name = "on: " + name;
        }
        return name;
    }

    @WorkerThread
    private void bookListChanging (boolean alwaysAudioBooks) {
        audioBooksBeingChanged = alwaysAudioBooks;
        if (alwaysAudioBooks || candidatesIsAudioBooks) {
            // The candidates directory is an AudioBooks directory,
            // and this operation changes the candidates directory.
            // Thus it changes the audioBooks directory, and we must deal with that.
            UiControllerBookList.suppressAnnounce();
            audioBooksBeingChanged = true;
        }
    }

    @WorkerThread
    private void bookListChanged () {
        // Synchronously update the book list, so it's never out of date with
        // respect to what we're doing here.
        // ??????????????? is there a cleaner design than the quasi-global?
        if (!audioBooksBeingChanged) {
            return;
        }

        booksModifiedUpdateComplete.prepare(); // Await completion event for Books Changed
        EventBus.getDefault().post(new MediaStoreUpdateEvent());
        booksModifiedUpdateComplete.await();

        // Wait until any pending duration queries complete
        awaitDurationQueries();
        UiControllerBookList.resumeAnnounce();
        audioBooksBeingChanged = false;
    }

    @WorkerThread
    Provisioning.BookInfo findInBookList(String str) {
        // Find if there's exactly one entry that matches the string.
        // >1 match means it's ambiguous, so we say no matches
        // Can accumulate multiple selected lines if repeatedly called.
        Provisioning.BookInfo match = null;
        for (Provisioning.BookInfo b : provisioning.bookList) {
            if (StringUtils.containsIgnoreCase(b.book.getDisplayTitle(), str)) {
                if (match != null) {
                    return null;
                }
                match = b;
            }
            else if (StringUtils.containsIgnoreCase(b.book.getDirectoryName(), str)) {
                if (match != null) {
                    return null;
                }
                match = b;
            }
        }
        if (match != null) {
            match.selected = true;
            return match;
        }
        return null;
    }

    @WorkerThread
    Provisioning.Candidate findInCandidatesList(String str) {
        // Find if there's exactly one entry that matches the string.
        // >1 match means it's ambiguous, so we say no matches
        // Can accumulate multiple selected lines if repeatedly called.
        Provisioning.Candidate match = null;
        for (Provisioning.Candidate c : provisioning.candidates) {
            if (StringUtils.containsIgnoreCase(c.bookTitle, str)) {
                if (match != null) {
                    return null;
                }
                match = c;
            }
            else if (StringUtils.containsIgnoreCase(c.oldDirPath, str)) {
                if (match != null) {
                    return null;
                }
                match = c;
            }
        }
        if (match != null) {
            match.isSelected = true;
            return match;
        }
        return null;
    }

    @WorkerThread
    boolean checkOperandsFor(@NonNull List<String> operands, String keyword) {
        for (int i=1; i<operands.size(); i++) {
            if (operands.get(i).equalsIgnoreCase(keyword)) {
                operands.remove(i);
                return true;
            }
        }
        return false;
    }

    @WorkerThread
    private String findOperandString(@NonNull List<String> operands) {
        // returns (de-quoted) string (and removes it)
        for (int i=1; i<operands.size(); i++) {
            String str = operands.get(i);
            if (str.length() >= 2 && str.charAt(0) == '"' && str.endsWith("\"")) {
                operands.remove(i);
                return str.substring(1,str.length()-1);
            }
        }
        return null;
    }

    @WorkerThread
    private String findOperandText(@NonNull List<String> operands) {
        // returns the next non-string word
        for (int i=1; i<operands.size(); i++) {
            String str = operands.get(i);
            if (str.charAt(0) != '"') {
                operands.remove(i);
                return str;
            }
        }
        return null;
    }

    String booleanOperand(List<String>operands) {
        if (operands.size() == 1) {
            return "query";
        }
        if (operands.size() > 2) {
            logActivityIndented( "Wrong number of operands for boolean");
        }
        boolean isTrue = checkOperandsFor(operands, "true")
            || checkOperandsFor(operands, "yes");
        checkOperandsFor(operands, "false");
        checkOperandsFor(operands, "no");

        if (errorIfAnyRemaining(operands)) {
            return "error";
        }

        return isTrue?"true":"false";
    }

    @WorkerThread
    boolean errorIfAnyRemaining(@NonNull List<String>operands) {
        // If we haven't consumed all operands (something unexpected), it's an error: print them
        if (operands.size() == 1) {
            return false;
        }
        for (int i = 1; i < operands.size(); i++) {
            String s = operands.get(i);
            logActivityIndented( "Unrecognized operand: " + s);
        }
        return true;
    }

    @WorkerThread
    String checkName(@NonNull String newName) {
        // Sanity checks possible new names
        if (newName.isEmpty()
                || !newName.matches("^\\p{Print}*$")
                || newName.matches(" +")) {
            logActivityIndented("New name cannot be empty or invisible");
            return null;
        }
        newName = AudioBook.filenameCleanup(newName);

        // enforce the "space" rule
        if (newName.indexOf(' ') < 0) {
            newName += " ";
        }

        buildBookList();
        String otherBook = provisioning.scanForDuplicateAudioBook(newName);
        if (otherBook != null) {
            logActivityIndented("New name '" + newName + "' collides with existing book '"
                + otherBook + "'");
            return null;
        }
        return newName;
    }

    private void setEarliestSavedAtTime(@NonNull Calendar nextStart) {
        if (!globalSettings.getSavedAtTime().after(processingStartTime)) {
            //If the saved time is before "now" (we just ran), just take the new time
            // (!after is <=)
            globalSettings.setSavedAtTime(nextStart);
        }
        else if (nextStart.before(globalSettings.getSavedAtTime())) {
            // The saved time is in the future; if this is sooner, use it instead.
            // (This can happen if there are several at or every commands queued in the mailbox.)
            globalSettings.setSavedAtTime(nextStart);
        }
    }

    public static void activate(boolean activate) {
        // Always deactivate it, just so we know things are clean
        WorkManager workManager = WorkManager.getInstance(getAppContext());
        workManager.cancelUniqueWork(TAG_WORK);

        if (activate) {
            PeriodicWorkRequest.Builder remoteAutoBuilder = new PeriodicWorkRequest
                    .Builder(RemoteAutoWorker.class, 15, TimeUnit.MINUTES);
            PeriodicWorkRequest request = remoteAutoBuilder.build();
            workManager.enqueueUniquePeriodicWork(TAG_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
        }
    }

    @WorkerThread
    public void awaitDurationQueries() {
        provisioning.assurePlaybackService();
        // Wait for the duration queries.
        List<AudioBook> audioBooks = audioBookManager.getAudioBooks();
        // Note: this can get reset if an update to the book list should occur asynchronously.
        // (Highly unlikely, but possible if the UI or a PC connection is used at the same
        // time as a remote update.)
        unSizedBooksRemaining = Integer.MAX_VALUE;

        booksChanged.prepare();
        while (true) {
            int howManyLeft = 0;
            // Keep trying until all the books have durations.
            boolean busy = false;
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (audioBooks) {
                for (AudioBook b : audioBooks) {
                    if (b.getTotalDurationMs() == AudioBook.UNKNOWN_POSITION) {
                        // below is no-op if it's already happening
                        // (N.B. we should never get to this point if there haven't
                        // already been duration queries made in other places... this is
                        // just to be sure.)

                        howManyLeft++;
                        if (howManyLeft <= 2) {
                            // Throttle so a big batch doesn't choke the device.
                            // The outer while guarantees we'll get back here.
                            provisioning.computeBookDuration(b);
                        }
                        busy = true;
                    }
                }
                if (howManyLeft < unSizedBooksRemaining) {
                    // We're making progress.
                    // Note: unSizedBooksRemaining can be reset if new books installed
                    unSizedBooksRemaining = howManyLeft;
                }
                else {
                    // No progress this time, but we got a done notification!
                    // Shouldn't happen, but just bail out. We'll see the problem elsewhere.
                    return;
                }
            }
            if (!busy) {
                return;
            }

            booksChanged.await();
        }
    }

    @SuppressWarnings({"UnusedParameters", "UnusedDeclaration"})
    public void onEvent(AudioBooksChangedEvent event) {
        // We just want to know it completed to move on
        booksModifiedUpdateComplete.resume();
        booksPreUseUpdateComplete.resume();

        // If this event occurs while waiting for duration calculation to finish,
        // reset the count because there might be new books.
        unSizedBooksRemaining = Integer.MAX_VALUE;
    }

    @SuppressWarnings({"UnusedParameters", "UnusedDeclaration"})
    public void onEvent(AnAudioBookChangedEvent event) {
        booksChanged.resume();
    }
}
//??????????????????????? clean up per-app storage on emulator
