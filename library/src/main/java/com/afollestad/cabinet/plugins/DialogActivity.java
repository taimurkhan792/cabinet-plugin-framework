package com.afollestad.cabinet.plugins;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.afollestad.materialdialogs.MaterialDialog;

/**
 * @author Aidan Follestad (afollestad)
 */
public class DialogActivity extends AppCompatActivity implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {

    private MaterialDialog mDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mDialog != null)
            mDialog.dismiss();
        final Bundle extras = getIntent().getExtras();
        final MaterialDialog.Builder builder = new MaterialDialog.Builder(this)
                .cancelable(false)
                .cancelListener(this)
                .dismissListener(this);
        if (extras.containsKey("error")) {
            builder.title(R.string.error)
                    .content(extras.getString("error", "?"))
                    .positiveText(android.R.string.ok);
        } else if (extras.containsKey("progress_message")) {
            builder.content(extras.getString("progress_message", "?"))
                    .progress(true, -1);
        }
        mDialog = builder.show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        if (!isFinishing())
            finish();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        finish();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }
}
