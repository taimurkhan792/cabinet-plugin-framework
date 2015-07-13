package com.afollestad.cabinet.plugins;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A PluginService is the heart of a plugin. It's started when a user of Cabinet taps on your plugin
 * in the navigation drawer, and it's responsible for listing files and performing other file actions.
 * <p/>
 * If you return something greater than zero for getForegroundId(), the Service will run in foreground
 * mode with a persistent notification until the user explicitly taps exit or you stop the service from within.
 * Tapping the notification will bring the user to your plugin in Cabinet's main UI, also.
 *
 * @author Aidan Follestad (afollestad)
 */
public abstract class PluginService extends Service {

    private final static boolean DEBUG = true;
    private Map<String, ChangeWatcher> mWatchers;
    private final Object LOCK = new Object();

    private void log(String message) {
        if (DEBUG)
            Log.d("Plugin:" + getPackageName(), message);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWatchers = new HashMap<>();
        log("onCreate");
    }

    public void showError(String error) {
        startActivity(new Intent(this, DialogActivity.class)
                .putExtra("error", error)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("onDestroy");

        synchronized (LOCK) {
            for (ChangeWatcher w : mWatchers.values())
                w.stopWatching();
            mWatchers.clear();
            mWatchers = null;
        }

        wipeDirectory(getCacheDir());
        wipeDirectory(getExternalCacheDir());
        sendBroadcast(new Intent(PluginConstants.EXIT_ACTION)
                .putExtra(PluginConstants.EXTRA_PACKAGE, getPackageName()));
    }

    private void wipeDirectory(File dir) {
        File[] cache = dir.listFiles();
        if (cache != null) {
            for (File fi : cache) {
                if (fi.isDirectory()) {
                    wipeDirectory(fi);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    fi.delete();
                }
            }
        }
    }

    private void watch(File local, PluginFile remote) {
        if (mWatchers == null) return;
        synchronized (LOCK) {
            removeExpiredWatchers();
            final String path = local.getAbsolutePath();
            if (mWatchers.containsKey(path)) return;
            ChangeWatcher watcher = new ChangeWatcher(path, remote, this);
            mWatchers.put(path, watcher);
        }
    }

    protected void removeExpiredWatchers() {
        synchronized (LOCK) {
            for (ChangeWatcher w : mWatchers.values()) {
                if (w.isExpired()) {
                    w.stopWatching();
                    mWatchers.remove(w.getPath());
                }
            }
        }
    }

    protected InputStream openInputStream(@NonNull Uri uri) throws Exception {
        if (uri.getScheme() == null || uri.getScheme().equalsIgnoreCase("file")) {
            return new FileInputStream(uri.getPath());
        } else if (uri.getScheme().equalsIgnoreCase("content")) {
            return getContentResolver().openInputStream(uri);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    protected OutputStream openOutputStream(@NonNull Uri uri) throws Exception {
        if (uri.getScheme() == null || uri.getScheme().equalsIgnoreCase("file")) {
            return new FileOutputStream(uri.getPath());
        } else if (uri.getScheme().equalsIgnoreCase("content")) {
            return getContentResolver().openOutputStream(uri);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    protected String getFileName(String path) {
        if (path != null) {
            if (path.endsWith(File.separator))
                path = path.substring(0, path.length() - 1);
            if (path.contains(File.separator)) {
                path = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                if (path.trim().isEmpty())
                    path = File.separator;
            }
        }
        return path;
    }

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            log("Received: " + intent.getAction());
            if (intent.getAction() != null) {
                if (PluginAuthenticator.AUTHENTICATED_ACTION.equals(intent.getAction()) ||
                        PluginAuthenticator.ACCOUNT_ADDED_ACTION.equals(intent.getAction()) ||
                        PluginAuthenticator.SETTINGS_ACTION.equals(intent.getAction())) {
                    // Update the current account to the newly added one
                    try {
                        setCurrentAccount(intent.getStringExtra(PluginAuthenticator.ACCOUNT_ID_EXTRA));
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
                    }
                    // Notify the main app
                    if (PluginAuthenticator.AUTHENTICATED_ACTION.equals(intent.getAction()))
                        refreshNotification(getString(R.string.waiting_for_cabinet));
                    startActivity(intent.setComponent(getMainAppComponent())
                            .putExtra(PluginConstants.EXTRA_PACKAGE, getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (PluginAuthenticator.CANCELLED_ACTION.equals(intent.getAction())) {
                    // Authentication was cancelled
                    exit();
                } else if (PluginConstants.EXIT_ACTION.equals(intent.getAction())) {
                    try {
                        startDisconnect();
                    } catch (Exception ignored) {
                    }
                    sendBroadcast(new Intent(PluginConstants.EXIT_ACTION)
                            .putExtra(PluginConstants.EXTRA_PACKAGE, getPackageName()));
                }
            }
        } else {
            log("Received: null intent");
        }
        return START_STICKY;
    }

    private void startConnect() throws Exception {
        if (authenticationNeeded()) {
            // Authentication needed
            refreshNotification(getString(R.string.authenticating), false);
            startActivity(getAuthenticatorIntent(true));
        } else {
            // Authentication not needed, connect now
            refreshNotification(getString(R.string.connecting));
            connect();
            refreshNotification(getString(R.string.connected));
        }
    }

    private void startDisconnect() throws Exception {
        refreshNotification(getString(R.string.disconnecting));
        disconnect();
        exit();
    }

    private void exit() {
        stopForeground(true);
        stopSelf();
    }

    private void refreshNotification(String status) {
        refreshNotification(status, true);
    }

    private void refreshNotification(String status, boolean allowExit) {
        if (status == null)
            status = getString(R.string.disconnected);
        if (getForegroundId() > 0) {
            try {
                PackageManager pm = getPackageManager();
                ServiceInfo info = pm.getServiceInfo(getComponentName(), PackageManager.GET_SERVICES);
                PendingIntent mainIntent = PendingIntent.getActivity(this, 1001,
                        new Intent(PluginConstants.VIEW_PLUGIN_ACTION)
                                .setComponent(getMainAppComponent())
                                .putExtra(PluginConstants.EXTRA_PACKAGE, getPackageName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_CANCEL_CURRENT);
                PendingIntent exitIntent = PendingIntent.getService(this, 1002,
                        new Intent(PluginConstants.EXIT_ACTION).setComponent(getComponentName()),
                        PendingIntent.FLAG_CANCEL_CURRENT);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                        .setContentTitle(info.loadLabel(pm))
                        .setContentText(status)
                        .setSmallIcon(info.getIconResource())
                        .setContentIntent(mainIntent);
                if (allowExit)
                    builder.addAction(R.drawable.ic_stat_navigation_close, getString(R.string.exit), exitIntent);
                startForeground(getForegroundId(), builder.build());
            } catch (Exception e) {
                Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * If you're using a web service like Google Drive, you'll need to authenticate the user before
     * files can be accessed. Return true if the user is not already authenticated, the Intent returned
     * in authenticator() will be launched as an Activity.
     */
    protected abstract boolean authenticationNeeded();

    /**
     * Return an Intent that can be used to start your Authenticator Activity.
     */
    protected abstract Intent authenticator();

    /**
     * Optional. Return an intent that can be used to start your Settings Activity. Your settings Activity
     * needs to extend PluginAuthenticator, like the authenticator does. You can re-use the same Activity
     * if you want.
     * <p/>
     * If you choose to use a settings screen, you must also add the meta-data flag to your service manifest tag.
     */
    protected abstract Intent settings();

    /**
     * The ID used for the persistent foreground notification that keeps the service running
     * indefinitely until disconnection. If you have multiple services in your plugin, each need a
     * unique ID.
     * <p/>
     * If you <= 0, the service will not go into foreground mode. Without foreground mode, no
     * persistent notification is displayed, but the Service will be eventually killed by the system.
     */
    protected abstract int getForegroundId();

    private ComponentName getComponentName() {
        String pkg = getPackageName();
        Class cls = getClass();
        return new ComponentName(pkg, cls.getName());
    }

    private ComponentName getMainAppComponent() {
        return new ComponentName("com.afollestad.cabinet", "com.afollestad.cabinet.ui.MainActivity");
    }

    private Intent getAuthenticatorIntent(boolean initial) {
        Intent intent = authenticator();
        if (intent == null)
            throw new IllegalStateException("Plugin " + getPackageName() + " must specify an authenticator.");
        return intent
                .setAction(initial ? PluginAuthenticator.AUTHENTICATED_ACTION : PluginAuthenticator.ACCOUNT_ADDED_ACTION)
                .putExtra(PluginAuthenticator.TARGET_COMPONENT, getComponentName().flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private Intent getSettingsIntent(String accountId, String accountDisplay, String initPath) {
        Intent intent = settings();
        if (intent == null) return null;
        return intent
                .setAction(PluginAuthenticator.SETTINGS_ACTION)
                .putExtra(PluginAuthenticator.TARGET_COMPONENT, getComponentName().flattenToString())
                .putExtra(PluginAuthenticator.ACCOUNT_ID_EXTRA, accountId)
                .putExtra(PluginAuthenticator.ACCOUNT_DISPLAY_EXTRA, accountDisplay)
                .putExtra(PluginAuthenticator.INITIAL_PATH_EXTRA, initPath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    protected abstract void connect() throws Exception;

    protected abstract Uri openFile(PluginFile file) throws Exception;

    private Uri performOpenFile(PluginFile file, boolean watch) throws Exception {
        Uri uri = openFile(file);
        if (watch && (uri.getScheme() == null || uri.getScheme().equals("file"))) {
            // Begins watching this local file for changes.
            // When changes are detected, upload() is called.
            watch(new File(uri.getPath()), file);
        }
        return uri;
    }

    protected abstract PluginFile upload(Uri local, PluginFile remote) throws Exception;

    protected abstract Uri download(PluginFile remote, Uri local) throws Exception;

    protected abstract List<PluginFile> listFiles(PluginFile parent) throws Exception;

    protected abstract PluginFile makeFile(String displayName, PluginFile parent) throws Exception;

    protected abstract PluginFile makeFolder(String displayName, PluginFile parent) throws Exception;

    protected abstract PluginFile copy(PluginFile source, PluginFile dest) throws Exception;

    protected abstract boolean remove(PluginFile file) throws Exception;

    protected abstract boolean exists(String path) throws Exception;

    protected abstract void chmod(int permissions, PluginFile target) throws Exception;

    protected abstract void chown(int uid, PluginFile target) throws Exception;

    protected abstract void disconnect() throws Exception;

    protected abstract boolean isConnected();

    protected abstract void setCurrentAccount(String accountId) throws Exception;

    protected abstract String getCurrentAccount();

    private void performRemoveAccount(String accountId) throws Exception {
        final String activeAccount = getCurrentAccount();
        if (activeAccount != null && activeAccount.equals(accountId)) {
            try {
                disconnect();
            } catch (Exception e) {
                throw new Exception("Failed to disconnect the active account before removal: " + e.getLocalizedMessage());
            }
        }
        removeAccount(accountId);
    }

    protected abstract void removeAccount(String accountId) throws Exception;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IPluginService.Stub mBinder = new IPluginService.Stub() {
        @Override
        public boolean authenticationNeeded() throws RemoteException {
            return PluginService.this.authenticationNeeded();
        }

        @Override
        public PluginErrorResult connect() throws RemoteException {
            if (isConnected())
                return new PluginErrorResult(getString(R.string.already_connected));
            try {
                PluginService.this.startConnect();
                return null;
            } catch (Exception e) {
                refreshNotification(getString(R.string.connect_error));
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }

        @Override
        public PluginUriResult openFile(PluginFile file, boolean watch) throws RemoteException {
            if (!isConnected())
                return new PluginUriResult(getString(R.string.not_connected), null);
            try {
                Uri uri = PluginService.this.performOpenFile(file, watch);
                return new PluginUriResult(null, uri);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginUriResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginFileResult upload(Uri local, PluginFile dest) throws RemoteException {
            refreshNotification(getString(R.string.uploading_files));
            try {
                PluginFile file = PluginService.this.upload(local, dest);
                return new PluginFileResult(null, file);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginFileResult(e.getLocalizedMessage(), null);
            } finally {
                refreshNotification(getString(R.string.connected));
            }
        }

        @Override
        public PluginUriResult download(PluginFile source, Uri dest) throws RemoteException {
            try {
                Uri uri = PluginService.this.download(source, dest);
                return new PluginUriResult(null, uri);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginUriResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginLsResult listFiles(PluginFile parent) throws RemoteException {
            if (!isConnected())
                return new PluginLsResult(getString(R.string.not_connected), null);
            try {
                List<PluginFile> results = PluginService.this.listFiles(parent);
                return new PluginLsResult(null, results);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginLsResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginFileResult makeFile(String displayName, PluginFile parent) throws RemoteException {
            if (!isConnected())
                return new PluginFileResult(getString(R.string.not_connected), null);
            try {
                PluginFile result = PluginService.this.makeFile(displayName, parent);
                return new PluginFileResult(null, result);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginFileResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginFileResult makeFolder(String displayName, PluginFile parent) throws RemoteException {
            if (!isConnected())
                return new PluginFileResult(getString(R.string.not_connected), null);
            try {
                PluginFile result = PluginService.this.makeFolder(displayName, parent);
                return new PluginFileResult(null, result);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginFileResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginFileResult copy(PluginFile source, PluginFile dest) throws RemoteException {
            if (!isConnected())
                return new PluginFileResult(getString(R.string.not_connected), null);
            try {
                PluginFile result = PluginService.this.copy(source, dest);
                return new PluginFileResult(null, result);
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginFileResult(e.getLocalizedMessage(), null);
            }
        }

        @Override
        public PluginErrorResult remove(PluginFile file) throws RemoteException {
            if (!isConnected())
                return new PluginErrorResult(getString(R.string.not_connected));
            try {
                if (!PluginService.this.remove(file))
                    return new PluginErrorResult("Unable to remove file or folder " + file);
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }

        @Override
        public PluginErrorResult chmod(int permissions, PluginFile target) throws RemoteException {
            if (!isConnected())
                return new PluginErrorResult(getString(R.string.not_connected));
            try {
                PluginService.this.chmod(permissions, target);
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }

        @Override
        public PluginErrorResult chown(int uid, PluginFile target) throws RemoteException {
            if (!isConnected())
                return new PluginErrorResult(getString(R.string.not_connected));
            try {
                PluginService.this.chown(uid, target);
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }

        @Override
        public boolean exists(String path) throws RemoteException {
            if (!isConnected())
                return false;
            try {
                return PluginService.this.exists(path);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        public void disconnect() throws RemoteException {
            try {
                PluginService.this.startDisconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void exit() throws RemoteException {
            if (PluginService.this.isConnected())
                disconnect();
            else {
                stopForeground(true);
                stopSelf();
            }
        }

        @Override
        public boolean isConnected() throws RemoteException {
            return PluginService.this.isConnected();
        }

        @Override
        public void openSettings(String accountId, String accountDisplay, String initPath) throws RemoteException {
            Intent intent = getSettingsIntent(accountId, accountDisplay, initPath);
            if (intent != null)
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @Override
        public void addAccount(boolean initial) throws RemoteException {
            startActivity(getAuthenticatorIntent(initial));
        }

        @Override
        public String getCurrentAccount() throws RemoteException {
            return PluginService.this.getCurrentAccount();
        }

        @Override
        public PluginErrorResult setCurrentAccount(String id) throws RemoteException {
            try {
                PluginService.this.setCurrentAccount(id);
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }

        @Override
        public PluginErrorResult removeAccount(String id) throws RemoteException {
            try {
                PluginService.this.performRemoveAccount(id);
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return new PluginErrorResult(e.getLocalizedMessage());
            }
        }
    };
}