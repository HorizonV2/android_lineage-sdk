/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-License-Identifier: Apache-2.0
 */
package lineageos.preference;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import lineageos.platform.Manifest;

import java.util.Objects;

/**
 * Base class for remote summary providers.
 * <p>
 * When an application is hosting preferences which are served by a different process,
 * the former needs to stay updated with changes in order to display the correct
 * summary when the user returns to the latter.
 * <p>
 * This class implements a simple ordered broadcast mechanism where the application
 * running the RemotePreference sends an explicit broadcast to the host, who
 * fills out the extras in the result bundle and returns it to the caller.
 * <p>
 * A minimal implementation will override getSummary and return a summary
 * for the given key. Alternatively, fillResultExtras can be overridden
 * if additional data should be added to the result.
 */
public class RemotePreferenceUpdater extends BroadcastReceiver {

    private static final String TAG = RemotePreferenceUpdater.class.getSimpleName();

    private static final boolean DEBUG = Log.isLoggable(
            RemotePreference.class.getSimpleName(), Log.VERBOSE);

    private static Intent getTargetIntent(Context context, String key) {
        final Intent i = new Intent(RemotePreference.ACTION_REFRESH_PREFERENCE);
        i.putExtra(RemotePreference.EXTRA_KEY, key);
        return i;
    }

    /**
     * Fetch the updated summary for the given key
     *
     * @param key
     * @return the summary for the given key
     */
    protected String getSummary(Context context, String key) {
        return null;
    }


    /**
     * Fill the bundle with the summary and any other data needed  to update
     * the client.
     *
     * @param context
     * @param key
     * @param extras
     * @return true if successful
     */
    protected boolean fillResultExtras(Context context, String key, Bundle extras) {
        extras.putString(RemotePreference.EXTRA_KEY, key);

        final String summary = getSummary(context, key);
        if (summary == null) {
            return false;
        }

        extras.putString(RemotePreference.EXTRA_SUMMARY, summary);
        return true;
    }


    /**
     * @hide
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (isOrderedBroadcast() &&
                RemotePreference.ACTION_UPDATE_PREFERENCE.equals(intent.getAction())) {
            final String key = intent.getStringExtra(RemotePreference.EXTRA_KEY);
            if (DEBUG) Log.d(TAG, "onReceive key=" +key +
                       " intent=" + Objects.toString(intent) +
                       " extras=" + Objects.toString(intent.getExtras()));

            if (key != null) {
                if (fillResultExtras(context, key, getResultExtras(true))) {
                    setResultCode(Activity.RESULT_OK);

                    if (DEBUG) Log.d(TAG, "onReceive result=" +
                               Objects.toString(getResultExtras(true)));
                    return;
                }
            }
            abortBroadcast();
        }
    }

    /**
     * Tell the RemotePreference that updated state is available. Call from
     * the fragment when necessary.
     *
     * @param context
     * @param key
     */
    public static void notifyChanged(Context context, String key) {
        if (DEBUG) Log.d(TAG, "notifyChanged: key=" + key +
                   " target=" + Objects.toString(getTargetIntent(context, key)));
        context.sendBroadcastAsUser(getTargetIntent(context, key),
                UserHandle.CURRENT, Manifest.permission.MANAGE_REMOTE_PREFERENCES);
    }
}
