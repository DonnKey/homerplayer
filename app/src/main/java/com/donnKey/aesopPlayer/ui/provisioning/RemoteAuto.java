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
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import static com.donnKey.aesopPlayer.AesopPlayerApplication.WEBSITE_URL;
import static com.donnKey.aesopPlayer.AesopPlayerApplication.getAppContext;
//import static com.donnKey.aesopPlayer.service.DemoSamplesInstallerService.enableTlsOnAndroid4;
import static com.donnKey.aesopPlayer.util.FilesystemUtil.isAudioPath;

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
    @Inject
    public Provisioning provisioning;

    @SuppressWarnings("FieldCanBeLocal")
    private final String controlFileName = "AesopScript.txt";
    @SuppressWarnings("FieldCanBeLocal")
    private final String resultFileName = "AesopResult.txt";

    private final Context appContext;
    private final File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    private final static String TAG="RemoteAuto";
    private final AwaitResume booksChanged = new AwaitResume();
    private final AwaitResume booksModifiedUpdateComplete = new AwaitResume();
    private final AwaitResume booksPreUseUpdateComplete = new AwaitResume();
    private final static String TAG_WORK = "Remote Auto";
    private final List<String> singleRequestResultLog = new ArrayList<>();
    private final List<String> compositeResultLog = new ArrayList<>();
    private final ArrayList<String> sendResultTo = new ArrayList<>();
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

    // Per request state
    private boolean retainBooks;
    private boolean archiveBooks;
    private boolean renameFiles;
    private boolean allowMobileData;
    private boolean useDownloadManager;
    private boolean forceDownloadManager;
    private Calendar messageSentTime;
    private int lineCounter; // counts non-comment lines
    private long interval;

    // For debugging
    @SuppressWarnings({"CanBeFinal"})
    private boolean consoleLogReport = false;
    @SuppressWarnings("CanBeFinal")
    private boolean consoleLog = false;

    @Singleton
    @UiThread
    public RemoteAuto() {
        appContext = getAppContext();
        AesopPlayerApplication.getComponent().inject(this);

        eventBus.register(this);
        if (BuildConfig.DEBUG) {
            // If debugging, the next line will always process the shared file at startup (once),
            // if shared files are enabled, for testing.
            //globalSettings.setSavedControlFileTimestamp(0);
            consoleLog = false;
            consoleLogReport = false;
        }

        // There are two ways to enable https on android 4. One uses Play Services, the
        // other involves programmatically enabling TLS directly. It looks as if there's
        // no way to get TLS 1.2 (or is that 1.3). Some websites work, some don't. This
        // (Play) way gives an expired certificate error. The other way gives an protocol error.
        // Unclear which is better. Leaving it at Play Services for now, but leaving dead code.
        // (Look for "Tls" in comments.) See also DemoSamplesInstallerService.
        try {
            ProviderInstaller.installIfNeeded(appContext);
        } catch (GooglePlayServicesRepairableException | GooglePlayServicesNotAvailableException e) {
            CrashWrapper.recordException(TAG,e);
            // Nothing much to do here, the app will attempt the download and most likely fail.
        }

    }

    public void pollSources(long interval) {
        this.interval  = interval;
        if (consoleLog) {
            Log.v("AESOP " + TAG
                    + " " + android.os.Process.myPid() + " " + Thread.currentThread().getId(),
                    "Poll cycle ========================================================");
        }

        if (firstTime) {
            // This often occurs very early in startup because of the way periodic work is scheduled.
            // Skip the first time.
            firstTime = false;
            return;
        }

        if (RemoteSettingsFragment.getInRemoteSettings()) {
            return; // we'll try again when it's not busy
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

        downloadManager = null;
        provisioning.releasePlaybackService();
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
        CrashWrapper.log(TAG, "Remote processing complete");
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

    @WorkerThread
    private void pollMail() {
        // AFAICT everything done here, and the installs, will time out with an error
        // if something goes wrong, so we don't need to worry about timeouts here.
        controlDir = new File(globalSettings.getRemoteControlDir());
        Mail mail = new Mail();
        try {
            if (mail.open() != Mail.SUCCESS) {
                // The interval between polls is long enough that a back-off is pointless.
                // (Note: We can't get here unless it worked once, so this is probably
                // either an external login change or some external connection problem.)
                return;
            }

            if (!mail.userFlagsSupported()) {
                logActivityIndented("This mailbox does not support using multiple device names. See https://donnKey.github.io/aesopPlayer/provisioning.html#device-name.");
            }

            // Search is for a pattern, case insensitive
            if (mail.readMail() != Mail.SUCCESS) {
                return;
            }

            for (Mail.Request request : mail) {
                continueProcessing = true;
                deleteMessage = true;
                processingStartTime = Calendar.getInstance();

                startReport();

                boolean skipProcessing = false;
                // We can't just ignore old mail because it might be an "run:every"... those
                // could hang around for years. We rely on deletion of ephemeral mail

                switch (request.checkSeen()) {
                    case Mail.NOT_SEEN:
                        // We've not seen this before: process normally
                        break;
                    case Mail.HAS_SEEN:
                        // We've processed this message, but presumably other devices haven't,
                        // so just ignore it. (When all process it, it'll be deleted.)
                        // run:every won't "delete" the message so we'll continue to see those.
                        continue;
                    case Mail.MISSING_DEVICE_NAME:
                        // A message with tags, and we don't have a device name: complain and skip
                        logActivityIndented("A device name must be set to use names on the Subject: line.");
                        skipProcessing = true;
                        break;
                    case Mail.MISSING_TAGS:
                        // A message without tags, and we have a device name: complain and skip
                        // (Shouldn't happen: mail filter should never allow one through.)
                        logActivityIndented("A device name must on the Subject: line if the device has a name.");
                        skipProcessing = true;
                        break;

                }

                messageSentTime = Calendar.getInstance();
                Date receivedAt = request.receivedTime();
                if (receivedAt != null) {
                    // if it's null, we'll approximate it with the Calendar default value of 'now'
                    messageSentTime.setTime(receivedAt);
                }


                if (!skipProcessing) {
                    // skipProcessing exists so we can report errors in mail/shared file
                    BufferedReader commands = request.getMessageBodyStream();
                    // If there's no plain-text MIME body section, we'll get null here.
                    if (commands == null) {
                        logActivityIndented("Mail message does not contain readable text, ignoring.");
                    }
                    else {
                        processCommands(commands);
                        CrashWrapper.log(TAG, "Remote processing complete");
                        try {
                            commands.close();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
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
        } finally {
            mail.close();
        }
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
        readLoop:
        while (continueProcessing) {
            String line;
            try {
                line = commands.readLine();
            } catch (IOException e) {
                return;
            }

            if (line == null) {
                return;
            }
            logActivity(line);

            if (line.isEmpty()) {
                continue;
            }

            // Split on whitespace, honoring quoted strings correctly, including handling
            // of escaped quotes. Since NUL is illegal in a filename...
            line = line.replace("\\\"", "\000");
            ArrayList<String> operands = new ArrayList<>(Arrays.asList(
                    line.split("\\p{javaSpaceChar}+(?=([^\"]*\"[^\"]*\")*[^\"]*$)")));
            if (operands.size() == 0) {
                continue;
            }
            if (operands.get(0).isEmpty()) {
                operands.remove(0);
            }

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
            while (op0.equals(">")) {
                // We remove "reply quotes" ('>') to make it easier to clone old messages
                operands.remove(0);
                if (operands.size() == 0) {
                    continue readLoop;
                }
                op0 = operands.get(0);
            }

            int pos = op0.indexOf(':');
            if (pos < 0) {
                logActivityIndented("Command '" + op0 + "' not recognized (missing ':')");
                continue;
            }

            provisioning.clearErrors();
            lineCounter++;

            if (lineCounter == 1) {
                if (op0.equals("run:every")) {
                    // We don't want to log anything until we've decided to do some work,
                    // but if this stuff crashes, it'd be nice to know it was due to this.
                    runCommands(operands);
                    if (!continueProcessing) {
                        break;
                    }
                    CrashWrapper.log(TAG, "Remote processing started");
                    continue;
                }
                CrashWrapper.log(TAG, "Remote processing started");
            }

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
                        case "ftp:":
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

                            File resultFile;
                            long ticks = System.nanoTime();
                            if (key.equals("ftp:")) {
                                resultFile = downloadFtp(op0);
                            }
                            else if (useDownloadManager) {
                                // Use the download manager in the hope that it's smarter and faster.
                                resultFile = downloadUsingManager(op0);
                            }
                            else {
                                // Use simple sockets. See above about https: on early devices.
                                resultFile = downloadUsingSockets(op0);
                            }
                            ticks = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - ticks);
                            if (resultFile == null) {
                                break;
                            }
                            logActivityIndented(String.format("Download time was: %dm%02ds", ticks/60, ticks%60));

                            if (downloadOnly) {
                                logActivityIndented("Download only of " + op0 + " as " + resultFile + " successful.");
                                break;
                            }

                            install(resultFile, newTitle, true);
                            break;
                        }
                        case "file:": {
                            // A local file.
                            if (downloadOnly) {
                                logActivityIndented("Error: downloadOnly illegal on file://");
                            } else {
                                File fileToInstall;
                                // Install the book named by the "file:" fileUrl
                                // We allow the otherwise illegal file:<relative pathname> (no slash after :) to mean
                                // an Android sdcard-local filename.
                                Uri uri = Uri.parse(op0);
                                String path = uri.getPath();

                                if (path == null) {
                                    // We know it's not correctly formed. We allow file:<name>
                                    // as a relative path - find relative to downloadDir's parent

                                    // (op0.substring(0,5).equals("file:") is always true here
                                    if (op0.charAt(5) != '/') {
                                        path = op0.substring(5);
                                        // a relative path - find relative to downloadDir's parent
                                        fileToInstall = new File(downloadDir.getParent(), path);
                                    }
                                    else {
                                        logActivityIndented("Could not parse file name");
                                        break;
                                    }
                                }
                                else {
                                    fileToInstall = new File(path);
                                }

                                install(fileToInstall, newTitle, false);
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
    private boolean isWiFiEnabled()
    {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert connectivityManager != null;

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

    /*
    // Mail can mangle strings, so this lets us see what we REALLY got.
    private static String hexChars(String input) {
        StringBuilder buf = new StringBuilder();
        for(int i = 0; i<input.length(); i++) {
            char c = input.charAt(i);
            if (c > ' ' && c < '\u007f') {
                buf.append(c);
            }
            else if (c < ' ') {
                buf.append((char)(c + 0x2400));
            }
            else if (c == '\u007f') {
                buf.append('\u2421');
            }
            else {
                buf.append("\\u");
                buf.append(String.format("%04x", (int)c));
            }
        }
        return buf.toString();
    }
     */

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

        URLConnection URLconn;
        int code;
        try {
            URLconn = url.openConnection();
        } catch (IOException e) {
            logActivityIndented("Cannot connect to host. " + e);
            return false;
        }

        if (URLconn instanceof HttpURLConnection) {
            HttpURLConnection connection = (HttpURLConnection)URLconn;
            connection.setConnectTimeout(1000);
            try {
                // So it opens (and closes!) quickly
                connection.setRequestMethod("HEAD");
                //enableTlsOnAndroid4(connection);
                code = connection.getResponseCode();
                connection.disconnect();
            } catch (IOException e) {
                connection.disconnect();
                logActivityIndented("Cannot connect to host. " + e);
                return false;
            }

            if (code == 200) {
                return true;
            }

            if (code == 404) {
                logActivityIndented( "File not found on host");
                return false;
            }
            logActivityIndented("Networking error " + code);
        }
        else {
            // Otherwise, it's an FtpURLConnection (by default), and we can't do much more.
            return true;
        }

        return false;
    }

    private File downloadUsingSockets(String requested) {
        try {
            Uri uri = Uri.parse(requested);
            String downloadFile = uri.getLastPathSegment();
            if (downloadFile == null) {
                logActivityIndented("Could not extract file name from URL.");
                return null;
            }
            File tmpFile = FilesystemUtil.createUniqueFilename(currentCandidateDir,downloadFile);
            if (tmpFile == null) {
                logActivityIndented("Too many identical download files: delete them.");
                return null;
            }

            Http http = new Http(null);
            return http.getFile_socket(requested, tmpFile);
        } catch (Exception e) {
            logActivityIndented("Http download failed: " + e.getMessage());
            return null;
        }
    }

    private File downloadUsingManager(String requested) {
        // The file manager computes it's own target filenames, so we don't provide one.
        try {
            Http http = new Http(downloadManager);
            return http.getFile_manager(requested);
        } catch (Exception e) {
            logActivityIndented("Http download failed: " + e.getMessage());
            return null;
        }
    }

    private File downloadFtp(String requested) {
        Uri uri = Uri.parse(requested);

        String host = uri.getHost();
        final int port = uri.getPort();
        String username = uri.getUserInfo();
        String downloadFile = uri.getPath();
        String downloadName = uri.getLastPathSegment();

        if (downloadFile == null || downloadName == null) {
            logActivityIndented("Filename part could not be parsed.");
            return null;
        }

        String password = null;
        if ((username != null) && username.contains(":")) {
            String[] subFields = username.split(":", 2);
            username = subFields[0];
            password = subFields[1];
        }

        File tmpFile = FilesystemUtil.createUniqueFilename(currentCandidateDir,downloadName);
        if (tmpFile == null) {
            logActivityIndented("Too many identical download files: delete them");
            return null;
        }

        try {
            Ftp ftp = new Ftp();
            ftp.getFile(host, port, username, password, downloadFile, tmpFile);
        } catch (Exception e) {
            logActivityIndented("Ftp download failed: " + e.getMessage());
            return null;
        }

        return tmpFile;
    }

    @WorkerThread
    private boolean install(@NonNull File fileToInstall, String title, boolean priorDownload) {
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

            logActivityIndented("Books  " + provisioning.getTotalTimeSubtitle());
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
                return;
            }

            logActivityIndented("Rename " + bookInfo.book.getPath().getPath() + " to \"" + newTitle + "\"");

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

            logActivityIndented("Downloaded books in " + currentCandidateDir.getPath());
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
            assert firstCandidate != null;

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
                // name + extra quotes + 2 quotes
                int len = files[i].length() + StringUtils.countMatches(files[i], '"') + 2;
                max = Math.max(len,max);
                lengths[i] = len;
            }

            final int columnWidth = 80;
            final int gutterWidth = 1;
            final int remaining = columnWidth - max;

            // A preliminary number of columns
            // assume a gutter width, and 2 character filenames (quotes already counted)
            int nColumns = (remaining/(gutterWidth + 2)) + 1;
            int nRows = files.length;
            int[] widthList = new int[nColumns];
            while (nColumns > 1) {
                nRows = (files.length+nColumns-1)/nColumns;

                for (int col = 0; col < nColumns; col++) {
                    int maxLen = 0; // the longest item in the current column
                    for (int row = 0; row < nRows; row++) {
                        int item = row * nColumns + col;
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

            if (nColumns == 1) {
                nRows = files.length;
                widthList[0] = max;
            }

            final StringBuilder text = new StringBuilder(Math.max(columnWidth, max)+1);
            final String emptySpace = StringUtils.repeat(' ', Math.max(columnWidth, max));
            for (int row = 0; row < nRows; row++) {
                for (int col = 0; col < nColumns; col++) {
                    int item = row * nColumns + col;
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
                    logActivityIndented("File " + toDelete.getPath() + " not found.");
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

                boolean gotDay = false;
                boolean matchedToday = false;
                for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
                    if (checkOperandsFor(operands, weekdays[d])) {
                        matchedToday |= d == todayDayName;
                        gotDay = true;
                    }
                    if (checkOperandsFor(operands, weekdays[d].substring(0,3))) {
                        matchedToday |= d == todayDayName;
                        gotDay = true;
                    }
                }

                if (checkOperandsFor(operands, "everyday")
                        || checkOperandsFor(operands, "every")) {
                    matchedToday = true;
                    gotDay = true;
                }

                if (checkOperandsFor(operands, "weekday")
                    || checkOperandsFor(operands, "week")) {
                    matchedToday |= (todayDayName >= Calendar.MONDAY && todayDayName <= Calendar.FRIDAY);
                    gotDay = true;
                }

                String timeString = findOperandText(operands);

                if (!gotDay) {
                    logActivityIndented("run:every No day names present");
                    continueProcessing = false;
                    deleteMessage = true;
                    return;
                }

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
                    if (consoleLog) {
                        Log.v("AESOP " + TAG,
                                "Run at:every -- do not run today; tomorrow at " + scheduledStartTime.getTime() + ".");
                    }
                    // (No log here! -- one isn't even generated)
                    return;
                }

                if (processingStartTime.before(scheduledStartTime)) {
                    // Later today
                    continueProcessing = false;
                    generateReport = false;
                    setEarliestSavedAtTime(scheduledStartTime);
                    if (consoleLog) {
                        Log.v("AESOP " + TAG,
                            "Run at:every -- run later today at " + scheduledStartTime.getTime() + ".");
                    }
                    return;
                }

                if (processingStartTime.getTimeInMillis() >= scheduledStartTime.getTimeInMillis() + interval) {
                    continueProcessing = false;
                    generateReport = false;
                    if (consoleLog) {
                        Log.v("AESOP " + TAG,
                                "Run at:every -- already run for today " + scheduledStartTime.getTime() + ".");
                    }
                    return;
                }

                // Earlier today (now!)
                logActivityIndented("Run at:every -- Starts at " + scheduledStartTime.getTime() + ".");

                // Set next scheduled start time as first-thing tomorrow
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
            Log.v("AESOP " + TAG + " " + Thread.currentThread().getId(), ">>>>log " + s);
        }
        singleRequestResultLog.add(s);
    }

    @WorkerThread
    private void logActivityIndented(String s) {
        if (consoleLog) {
            Log.v("AESOP " + TAG + " " + Thread.currentThread().getId(), ">>>>log      " + s);
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
            Log.v("AESOP " + TAG, "**********************************");
            for (String s : singleRequestResultLog) {
                Log.v("AESOP " + TAG, "Log: " + s);
            }
            Log.v("AESOP " + TAG, "**********************************");
        }

        compositeResultLog.addAll(singleRequestResultLog);
        singleRequestResultLog.clear();
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
                    .setSubject("Aesop results for request " + getDeviceTag());
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
                    logActivityIndented("Multiple matches for " + str);
                    return null;
                }
                match = b;
            }
            else if (StringUtils.containsIgnoreCase(b.book.getDirectoryName(), str)) {
                if (match != null) {
                    logActivityIndented("Multiple matches for " + str);
                    return null;
                }
                match = b;
            }
        }
        if (match != null) {
            match.selected = true;
            return match;
        }
        logActivityIndented("No matches found for " + str);
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
                    logActivityIndented("Multiple matches for " + str);
                    return null;
                }
                match = c;
            }
            else if (StringUtils.containsIgnoreCase(c.oldDirPath, str)) {
                if (match != null) {
                    logActivityIndented("Multiple matches for " + str);
                    return null;
                }
                match = c;
            }
        }
        if (match != null) {
            match.isSelected = true;
            return match;
        }
        logActivityIndented("No matches found for " + str);
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
            logActivityIndented("New name \"" + newName + "\" collides with existing book \""
               + otherBook + "\"");
            return null;
        }
        return newName;
    }

    private void setEarliestSavedAtTime(@NonNull Calendar nextStart) {
        // This is for the control file... mail continues to be polled regularly
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
        WorkManager workManager = WorkManager.getInstance(getAppContext());

        if (activate) {
            CrashWrapper.log(TAG, "Worker activated");
            PeriodicWorkRequest.Builder remoteAutoBuilder = new PeriodicWorkRequest
                    .Builder(RemoteAutoWorker.class, 15, TimeUnit.MINUTES);
            PeriodicWorkRequest request = remoteAutoBuilder.build();
            workManager.enqueueUniquePeriodicWork(TAG_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
        }
        else {
            CrashWrapper.log(TAG, "Worker disabled");
            workManager.cancelUniqueWork(TAG_WORK);
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
    @Subscribe
    public void onEvent(AudioBooksChangedEvent event) {
        // We just want to know it completed to move on
        booksModifiedUpdateComplete.resume();
        booksPreUseUpdateComplete.resume();

        // If this event occurs while waiting for duration calculation to finish,
        // reset the count because there might be new books.
        unSizedBooksRemaining = Integer.MAX_VALUE;
    }

    @SuppressWarnings({"UnusedParameters", "UnusedDeclaration"})
    @Subscribe
    public void onEvent(AnAudioBookChangedEvent event) {
        booksChanged.resume();
    }
}
