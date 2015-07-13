package com.afollestad.cabinet.plugins;

import android.net.Uri;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * @author Aidan Follestad (afollestad)
 */
class ChangeWatcher extends FileObserver {

    private final static long UPLOAD_DELAY = 150;

    private final String mPath;
    private final PluginFile mRemote;
    private final PluginService mService;
    private Timer mTimer;
    private long mAccess;

    private void log(String message) {
        Log.d("ChangeWatcher", message);
    }

    public ChangeWatcher(String path, PluginFile remote, PluginService service) {
        super(path);
        log("Watching: " + path);
        mPath = path;
        mRemote = remote;
        mService = service;
        mAccess = System.currentTimeMillis();
        startWatching();
    }

    private void queueUpload() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
        }
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    mService.upload(Uri.fromFile(new File(mPath)), mRemote);
                } catch (Exception e) {
                    mService.showError(mService.getString(
                            R.string.failed_upload_error, mPath, e.getLocalizedMessage()));
                }
            }
        }, UPLOAD_DELAY);
    }

    @Override
    public void onEvent(int event, String path) {
        if (event == FileObserver.CLOSE_WRITE) {
            mAccess = System.currentTimeMillis();
            log(mPath + " was modified.");
            queueUpload();
        } else if (event == FileObserver.DELETE || event == FileObserver.DELETE_SELF) {
            log(mPath + " was deleted, stopping self.");
            mAccess = -1;
            mService.removeExpiredWatchers();
        }
    }

    @Override
    public void stopWatching() {
        super.stopWatching();
        log("Unwatching: " + mPath);
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
    }

    public String getPath() {
        return mPath;
    }

    public boolean isExpired() {
        if (mAccess == -1) return true;
        final long now = System.currentTimeMillis();
        final long fifteenMinutes = TimeUnit.MINUTES.toMillis(15);
        final boolean expired = ((now - mAccess) >= fifteenMinutes);
        if (expired) {
            log("Watcher expired: " + mPath);
            //noinspection ResultOfMethodCallIgnored
            new File(mPath).delete();
        }
        return expired;
    }
}
