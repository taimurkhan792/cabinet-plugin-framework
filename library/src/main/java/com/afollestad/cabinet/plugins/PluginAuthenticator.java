package com.afollestad.cabinet.plugins;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

/**
 * An Authenticator is an Activity invoked by a PluginService when authentication is needed. The
 * Authenticator is responsible for displaying any necessary login UI and persisting values that are
 * used by the Service to verify authentication.
 * <p/>
 * For an example, a Google Drive plugin could using Google Play Services to authenticate the user
 * with a Google account. The account token would be saved in SharedPreferences for use in the plugin's
 * main service.
 *
 * @author Aidan Follestad (afollestad)
 */
@SuppressWarnings("ConstantConditions")
@SuppressLint("MissingSuperCall")
public class PluginAuthenticator extends AppCompatActivity {

    private String mTargetComponent;
    private boolean mNotified;
    private String mInitialPath;
    private String mAccountDisplay;

    public final static String AUTHENTICATED_ACTION = "com.afollestad.cabinet.plugins.AUTHENTICATED";
    public final static String SETTINGS_ACTION = "com.afollestad.cabinet.plugins.SETTINGS";
    public final static String ACCOUNT_ADDED_ACTION = "com.afollestad.cabinet.plugins.ACCOUNT_ADDED";
    public final static String CANCELLED_ACTION = "com.afollestad.cabinet.plugins.AUTHENTICATION_CANCELLED";

    public final static String TARGET_COMPONENT = "target_component";
    public final static String ACCOUNT_ID_EXTRA = "com.afollestad.cabinet.plugins.ACCOUNT_ID";
    public final static String INITIAL_PATH_EXTRA = "com.afollestad.cabinet.plugins.INITIAL_PATH";
    public final static String ACCOUNT_DISPLAY_EXTRA = "com.afollestad.cabinet.plugins.ACCOUNT_DISPLAY";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isAddingAccount())
            setTitle(R.string.add_account);
        else if (isSettings())
            setTitle(R.string.settings);

        mTargetComponent = getIntent().getStringExtra(TARGET_COMPONENT);
        if (mTargetComponent == null || mTargetComponent.trim().isEmpty())
            throw new IllegalStateException("No target component set for PluginAuthenticator.");
        else if (savedInstanceState != null) {
            mTargetComponent = savedInstanceState.getString("target_component");
            mNotified = savedInstanceState.getBoolean("notified");
            mInitialPath = savedInstanceState.getString("initial_path");
            mAccountDisplay = savedInstanceState.getString("account_display");
        } else {
            mInitialPath = getIntent().getStringExtra(INITIAL_PATH_EXTRA);
            mAccountDisplay = getIntent().getStringExtra(ACCOUNT_DISPLAY_EXTRA);
        }
    }

    /**
     * Returns true if the authenticator was started during initial plugin setup.
     */
    protected final boolean isInitialSetup() {
        return !isSettings() && !isAddingAccount();
    }

    /**
     * Returns true if the authenticator was started to add account, rather than initial plugin setup.
     */
    protected final boolean isAddingAccount() {
        return ACCOUNT_ADDED_ACTION.equals(getIntent().getAction());
    }

    /**
     * Returns true if the authenticator was started to change account settings.
     */
    protected final boolean isSettings() {
        return SETTINGS_ACTION.equals(getIntent().getAction());
    }

    protected final String getAccountId() {
        return getIntent().getStringExtra(ACCOUNT_ID_EXTRA);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("target_component", mTargetComponent);
        outState.putBoolean("notified", mNotified);
        outState.putString("initial_path", mInitialPath);
        outState.putString("account_display", mAccountDisplay);
    }

    private Intent getServiceIntent() {
        return new Intent(getIntent().getAction())
                .setComponent(ComponentName.unflattenFromString(mTargetComponent))
                .putExtra(PluginConstants.EXTRA_PACKAGE, getPackageName())
                .putExtra(INITIAL_PATH_EXTRA, mInitialPath)
                .putExtra(ACCOUNT_DISPLAY_EXTRA, mAccountDisplay);
    }

    /**
     * Sets a display name for the account being authenticated/added. This will be shown to users
     * in Cabinet's navigation drawer and bread crumbs when this account is active.
     */
    public final void setAccountDisplay(@NonNull String display) {
        mAccountDisplay = display;
    }

    /**
     * Sets the initial path navigated to when this account becomes active. E.g, when the user
     * taps this account from the navigation drawer in Cabinet.
     */
    public final void setInitialPath(@Nullable String initialPath) {
        mInitialPath = initialPath;
    }

    /**
     * Finishes the Activity and notifies the service and main app that the plugin is authenticated.
     *
     * @param accountId The account ID that was authenticated. Only necessary if your plugin uses accounts.
     */
    public final void finish(@Nullable String accountId) {
        startService(getServiceIntent()
                .putExtra(ACCOUNT_ID_EXTRA, accountId));
        super.finish();
        mNotified = true;
    }

    /**
     * Finishes the Activity and notifies the service and main app that authentication or changing
     * settings was cancelled.
     */
    public final void cancel() {
        mNotified = false;
        super.finish();
    }

    /**
     * @deprecated Use {@link #finish(String)} or {@link #cancel()} instead.
     */
    @Override
    @Deprecated
    public void finish() {
        cancel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!mNotified) {
            // Notify the service that it should kill itself
            startService(getServiceIntent().setAction(CANCELLED_ACTION));
            mNotified = true;
        }
        if (!isFinishing() && !isChangingConfigurations()) {
            // Don't allow the Activity to stay open when the user presses the home button
            super.finish();
        }
    }
}