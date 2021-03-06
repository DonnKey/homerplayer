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
package com.donnKey.aesopPlayer.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.donnKey.aesopPlayer.BuildConfig;
import com.donnKey.aesopPlayer.analytics.CrashWrapper;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;
import com.google.common.base.Preconditions;
import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.R;
import com.donnKey.aesopPlayer.events.DemoSamplesInstallationFinishedEvent;
import com.donnKey.aesopPlayer.events.MediaStoreUpdateEvent;
import com.donnKey.aesopPlayer.model.DemoSamplesInstaller;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

import org.greenrobot.eventbus.EventBus;

@MainThread
public class DemoSamplesInstallerService extends Service {

    private static final String CLASS_NAME = "DemoSamplesInstallerService";
    private static final String TAG = "DemoSamplesInst";
    private static final String ACTION_EXTRA = "action";
    private static final int ACTION_START_DOWNLOAD = 0;
    private static final int ACTION_CANCEL_DOWNLOAD = 1;

    private static final int DOWNLOAD_BUFFER_SIZE = 32767;
    private static final long MIN_PROGRESS_UPDATE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int NOTIFICATION_ID = R.string.demo_samples_service_notification_download;

    public static final String BROADCAST_DOWNLOAD_PROGRESS_ACTION =
            CLASS_NAME + ".PROGRESS";
    public static final String PROGRESS_BYTES_EXTRA = "progressBytes";
    public static final String TOTAL_BYTES_EXTRA = "totalBytes";
    public static final String BROADCAST_INSTALL_STARTED_ACTION =
            CLASS_NAME + ".INSTALL_STARTED";
    public static final String BROADCAST_INSTALL_FINISHED_ACTION =
            CLASS_NAME + ".INSTALL_FINISHED";
    public static final String BROADCAST_FAILED_ACTION =
            CLASS_NAME + ".FAILED";

    @NonNull
    public static Intent createDownloadIntent(Context context, Uri downloadUri) {
        Intent intent = new Intent(context, DemoSamplesInstallerService.class);
        intent.setData(downloadUri);
        intent.putExtra(ACTION_EXTRA, ACTION_START_DOWNLOAD);
        return intent;
    }

    @NonNull
    public static Intent createCancelIntent(Context context) {
        Intent intent = new Intent(context, DemoSamplesInstallerService.class);
        intent.putExtra(ACTION_EXTRA, ACTION_CANCEL_DOWNLOAD);
        return intent;
    }

    // It's a bit ugly the the service communicates with other components both via
    // a LocalBroadcastManager and an EventBus.
    @Inject public EventBus eventBus;
    private DownloadAndInstallThread downloadAndInstallThread;
    private boolean isDownloading = false;
    private long lastProgressUpdateNanos = 0;
    private static DemoSamplesInstallerService instance;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        int action = intent.getIntExtra(ACTION_EXTRA, -1);
        switch(action) {
            case ACTION_START_DOWNLOAD: {
                CrashWrapper.log(TAG + ": starting download");
                Preconditions.checkState(downloadAndInstallThread == null);
                String downloadUri = intent.getDataString();

                Notification notification = NotificationUtil.createForegroundServiceNotification(
                        getApplicationContext(),
                        R.string.demo_samples_service_notification_download,
                        android.R.drawable.stat_sys_download);
                startForeground(NOTIFICATION_ID, notification);

                try {
                    ResultHandler result = new ResultHandler(this, getMainLooper());
                    isDownloading = true;
                    downloadAndInstallThread = new DownloadAndInstallThread(this, result, downloadUri);
                    downloadAndInstallThread.start();
                } catch (MalformedURLException e) {
                    onFailed(e.getMessage() != null ? e.getMessage() : TAG + ": start exception message unknown");
                }
                break;
            }
            case ACTION_CANCEL_DOWNLOAD:
                CrashWrapper.log(TAG + ": cancelling download");
                if (downloadAndInstallThread != null) {
                    isDownloading = false;
                    downloadAndInstallThread.interrupt();
                    downloadAndInstallThread = null;
                    stopForeground(true);
                }
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashWrapper.log(TAG + ": created");
        AesopPlayerApplication.getComponent().inject(this);
        instance = this;
    }

    @Override
    public void onDestroy() {
        CrashWrapper.log(TAG + ": destroying");
        if (downloadAndInstallThread != null)
            downloadAndInstallThread.interrupt();
        instance = null;
        super.onDestroy();
    }

    public static boolean isDownloading() {
        // This is a hack relying on the fact that only one service instance may be started
        // and that the service is local.
        return instance != null && instance.isDownloading;
    }

    public static boolean isInstalling() {
        // This is a hack relying on the fact that only one service instance may be started
        // and that the service is local.
        return instance != null && instance.downloadAndInstallThread != null
                && !instance.isDownloading;
    }

    private void onInstallStarted() {
        CrashWrapper.log(TAG + ": install started");
        isDownloading = false;
        Intent intent = new Intent(BROADCAST_INSTALL_STARTED_ACTION);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = NotificationUtil.createForegroundServiceNotification(
                getApplicationContext(),
                R.string.demo_samples_service_notification_install,
                android.R.drawable.stat_sys_download_done);
        Objects.requireNonNull(notificationManager).notify(NOTIFICATION_ID, notification);
    }

    private void onInstallFinished() {
        CrashWrapper.log(TAG + ": install finished");
        Intent intent = new Intent(BROADCAST_INSTALL_FINISHED_ACTION);
        eventBus.post(new DemoSamplesInstallationFinishedEvent(true, null));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        // MediaStoreUpdateEvent may change the state of the UI, send it as the last action.
        eventBus.post(new MediaStoreUpdateEvent());
        stopSelf();
    }

    private void onFailed(@NonNull String errorMessage) {
        CrashWrapper.log(TAG,"download or install failed: " + errorMessage);
        isDownloading = false;
        eventBus.post(new DemoSamplesInstallationFinishedEvent(false, errorMessage));
        Intent intent = new Intent(BROADCAST_FAILED_ACTION);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        stopSelf();
    }

    private void onProgress(int transferredBytes, int totalBytes) {
        long nowNanos = System.nanoTime();
        if (nowNanos - lastProgressUpdateNanos > MIN_PROGRESS_UPDATE_INTERVAL_NANOS
                || transferredBytes == totalBytes) {
            Intent intent = new Intent(BROADCAST_DOWNLOAD_PROGRESS_ACTION);
            intent.putExtra(PROGRESS_BYTES_EXTRA, transferredBytes);
            intent.putExtra(TOTAL_BYTES_EXTRA, totalBytes);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            lastProgressUpdateNanos = nowNanos;
        }
    }

    private static class ResultHandler {

        private final DemoSamplesInstallerService service;
        private final Handler handler;

        private ResultHandler(DemoSamplesInstallerService service, Looper looper) {
            this.service = service;
            this.handler = new Handler(looper);
        }

        void onInstallFinished() {
            handler.post(service::onInstallFinished);
        }

        void onFailed(final @NonNull String errorMessage) {
            handler.post(() -> service.onFailed(errorMessage));
        }

        void onDownloadProgress(final int progress, final int total) {
            handler.post(() -> service.onProgress(progress, total));
        }

        void onInstallStarted() {
            handler.post(service::onInstallStarted);
        }
    }

    private static class DownloadAndInstallThread extends Thread {

        private final Context context;
        private final ResultHandler resultHandler;
        private final URL downloadUrl;

        DownloadAndInstallThread(Context context, ResultHandler resultHandler, String downloadUrl)
                throws MalformedURLException {
            super("DownloadThread");
            this.context = context;
            this.resultHandler = resultHandler;
            this.downloadUrl = new URL(downloadUrl);
        }

        @WorkerThread
        @Override
        public void run() {
            try {
                File tmpFile = downloadSamples();

                if (isInterrupted())
                    return;  // No result expected.

                resultHandler.onInstallStarted();
                DemoSamplesInstaller installer =
                        AesopPlayerApplication.getComponent().createDemoSamplesInstaller();
                installer.installBooksFromZip(tmpFile);

                resultHandler.onInstallFinished();
            } catch (IOException e) {
                resultHandler.onFailed(e.getMessage() != null ? e.getMessage() : TAG + ": run exception message unknown");
            }
        }

        @NonNull
        @WorkerThread
        private File downloadSamples() throws IOException {
            byte[] inputBuffer = new byte[DOWNLOAD_BUFFER_SIZE];
            File tmpFile = File.createTempFile("download", null, context.getExternalCacheDir());
            tmpFile.deleteOnExit();

            // There are two ways to enable https on android 4. One uses Play Services, the
            // other involves programmatically enabling TLS directly. It looks as if there's
            // no way to get TLS 1.2 (or is that 1.3). Some websites work, some don't. This
            // (Play) way gives an expired certificate error. The other way gives an protocol error.
            // Unclear which is better. Leaving it at Play Services for now, but leaving dead code.
            // (Look for "Tls" in comments.) See also RemoteAuto.
            try {
                ProviderInstaller.installIfNeeded(context);
            } catch (GooglePlayServicesRepairableException | GooglePlayServicesNotAvailableException e) {
                CrashWrapper.recordException(TAG,e);
                // Nothing much to do here, the app will attempt the download and most likely fail.
            }

            OutputStream output = new BufferedOutputStream(new FileOutputStream(tmpFile));
            // The samples file is at AesopPlayerApplication#DEMO_SAMPLES_URL
            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection)downloadUrl.openConnection();
                if (!BuildConfig.DEBUG) {
                    if (!(connection instanceof HttpsURLConnection)) {
                        throw new IOException();
                    }
                }
            } catch (Exception e) {
                throw new IOException("The server for the samples is not correctly configured (not https?)");
            }
            // Disable gzip, apparently Java and/or Android's okhttp has problems with it
            // (possibly https://bugs.java.com/bugdatabase/view_bug.do?bug_id=7003462).
            connection.setRequestProperty("accept-encoding", "identity");
            //enableTlsOnAndroid4(connection);
            InputStream input = new BufferedInputStream(connection.getInputStream());

            int totalBytesRead = 0;
            int bytesRead;
            while((bytesRead = input.read(inputBuffer, 0, inputBuffer.length)) > 0) {
                output.write(inputBuffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                resultHandler.onDownloadProgress(totalBytesRead, connection.getContentLength());

                if (isInterrupted())
                    break;
            }
            output.close();

            connection.disconnect();

            return tmpFile;
        }
    }

    /*
    public static void enableTlsOnAndroid4(HttpURLConnection connection) {
        // The internets say that this may be also needed on some API 21 phones...
        if (Build.VERSION.SDK_INT <= 21) {
            if (connection instanceof HttpsURLConnection) {
                try {
                    ((HttpsURLConnection)connection).setSSLSocketFactory(new TlsSSLSocketFactory());
                } catch (KeyManagementException | NoSuchAlgorithmException e) {
                    CrashWrapper.recordException(e);
                    // Nothing much to do here, the app will attempt the download and most likely
                    // fail.
                }
            }
        }
    }
     */
}
