/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2019,2021 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineagesettings;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;

/**
 * The LineageSettingsProvider serves as a {@link ContentProvider} for Lineage specific settings
 */
public class LineageSettingsProvider extends ContentProvider {
    public static final String TAG = "LineageSettingsProvider";
    private static final boolean LOCAL_LOGV = false;

    private static final boolean USER_CHECK_THROWS = true;

    private static final Bundle NULL_SETTING = Bundle.forPair("value", null);

    // Each defined user has their own settings
    protected final SparseArray<LineageDatabaseHelper> mDbHelpers = new SparseArray<LineageDatabaseHelper>();

    private static final int SYSTEM = 1;
    private static final int SECURE = 2;
    private static final int GLOBAL = 3;

    private static final int SYSTEM_ITEM_NAME = 4;
    private static final int SECURE_ITEM_NAME = 5;
    private static final int GLOBAL_ITEM_NAME = 6;

    private static final String ITEM_MATCHER = "/*";
    private static final String NAME_SELECTION = Settings.NameValueTable.NAME + " = ?";

    // Must match definitions in fw/b
    // packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java
    public static final String RESULT_ROWS_DELETED  = "result_rows_deleted";
    public static final String RESULT_SETTINGS_LIST = "result_settings_list";

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM,
                SYSTEM);
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_SECURE,
                SECURE);
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL,
                GLOBAL);
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM +
                ITEM_MATCHER, SYSTEM_ITEM_NAME);
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_SECURE +
                ITEM_MATCHER, SECURE_ITEM_NAME);
        sUriMatcher.addURI(LineageSettings.AUTHORITY, LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL +
                ITEM_MATCHER, GLOBAL_ITEM_NAME);
    }

    private UserManager mUserManager;
    private Uri.Builder mUriBuilder;
    private SharedPreferences mSharedPrefs;

    @Override
    public boolean onCreate() {
        if (LOCAL_LOGV) Log.d(TAG, "Creating LineageSettingsProvider");

        mUserManager = UserManager.get(getContext());

        establishDbTracking(UserHandle.USER_SYSTEM);

        mUriBuilder = new Uri.Builder();
        mUriBuilder.scheme(ContentResolver.SCHEME_CONTENT);
        mUriBuilder.authority(LineageSettings.AUTHORITY);

        mSharedPrefs = getContext().getSharedPreferences(TAG, Context.MODE_PRIVATE);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_SYSTEM);
                String action = intent.getAction();

                if (LOCAL_LOGV) Log.d(TAG, "Received intent: " + action + " for user: " + userId);

                if (action.equals(Intent.ACTION_USER_REMOVED)) {
                    onUserRemoved(userId);
                }
            }
        }, userFilter);

        return true;
    }

    /**
     * Performs cleanup for the removed user.
     * @param userId The id of the user that is removed.
     */
    private void onUserRemoved(int userId) {
        synchronized (this) {
            // the db file itself will be deleted automatically, but we need to tear down
            // our helpers and other internal bookkeeping.

            mDbHelpers.delete(userId);

            if (LOCAL_LOGV) Log.d(TAG, "User " + userId + " is removed");
        }
    }

    // region Content Provider Methods

    @Override
    public Bundle call(String method, String request, Bundle args) {
        if (LOCAL_LOGV) Log.d(TAG, "Call method: " + method + " " + request);

        int callingUserId = UserHandle.getCallingUserId();
        if (args != null) {
            int reqUser = args.getInt(LineageSettings.CALL_METHOD_USER_KEY, callingUserId);
            if (reqUser != callingUserId) {
                callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), reqUser, false, true,
                        "get/set setting for user", null);
                if (LOCAL_LOGV) Log.v(TAG, "   access setting for user " + callingUserId);
            }
        }

        switch (method) {
            // Get methods
            case LineageSettings.CALL_METHOD_GET_SYSTEM:
                return lookupSingleValue(callingUserId, LineageSettings.System.CONTENT_URI,
                        request);
            case LineageSettings.CALL_METHOD_GET_SECURE:
                return lookupSingleValue(callingUserId, LineageSettings.Secure.CONTENT_URI,
                        request);
            case LineageSettings.CALL_METHOD_GET_GLOBAL:
                return lookupSingleValue(callingUserId, LineageSettings.Global.CONTENT_URI,
                        request);

            // Put methods
            case LineageSettings.CALL_METHOD_PUT_SYSTEM:
                enforceWritePermission(lineageos.platform.Manifest.permission.WRITE_SETTINGS);
                callHelperPut(callingUserId, LineageSettings.System.CONTENT_URI, request, args);
                return null;
            case LineageSettings.CALL_METHOD_PUT_SECURE:
                enforceWritePermission(
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS);
                callHelperPut(callingUserId, LineageSettings.Secure.CONTENT_URI, request, args);
                return null;
            case LineageSettings.CALL_METHOD_PUT_GLOBAL:
                enforceWritePermission(
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS);
                callHelperPut(callingUserId, LineageSettings.Global.CONTENT_URI, request, args);
                return null;

            // List methods
            case LineageSettings.CALL_METHOD_LIST_SYSTEM:
                return callHelperList(callingUserId, LineageSettings.System.CONTENT_URI);
            case LineageSettings.CALL_METHOD_LIST_SECURE:
                return callHelperList(callingUserId, LineageSettings.Secure.CONTENT_URI);
            case LineageSettings.CALL_METHOD_LIST_GLOBAL:
                return callHelperList(callingUserId, LineageSettings.Global.CONTENT_URI);

            // Delete methods
            case LineageSettings.CALL_METHOD_DELETE_SYSTEM:
                enforceWritePermission(lineageos.platform.Manifest.permission.WRITE_SETTINGS);
                return callHelperDelete(callingUserId, LineageSettings.System.CONTENT_URI,
                        request);
            case LineageSettings.CALL_METHOD_DELETE_SECURE:
                enforceWritePermission(
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS);
                return callHelperDelete(callingUserId, LineageSettings.Secure.CONTENT_URI,
                        request);
            case LineageSettings.CALL_METHOD_DELETE_GLOBAL:
                enforceWritePermission(
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS);
                return callHelperDelete(callingUserId, LineageSettings.Global.CONTENT_URI,
                        request);
        }

        return null;
    }

    private void enforceWritePermission(String permission) {
        if (getContext().checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    String.format("Permission denial: writing to settings requires %s",
                            permission));
        }
    }

    // Helper for call() CALL_METHOD_DELETE_* methods
    private Bundle callHelperDelete(int callingUserId, Uri contentUri, String key) {
        final int rowsDeleted = deleteForUser(callingUserId, contentUri, NAME_SELECTION,
                new String[]{ key });
        final Bundle ret = new Bundle();
        ret.putInt(RESULT_ROWS_DELETED, rowsDeleted);
        return ret;
    }

    // Helper for call() CALL_METHOD_LIST_* methods
    private Bundle callHelperList(int callingUserId, Uri contentUri) {
        final ArrayList<String> lines = new ArrayList<String>();
        final Cursor cursor = queryForUser(callingUserId, contentUri, null, null, null, null);
        try {
            while (cursor != null && cursor.moveToNext()) {
                lines.add(cursor.getString(1) + "=" + cursor.getString(2));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        final Bundle ret = new Bundle();
        ret.putStringArrayList(RESULT_SETTINGS_LIST, lines);
        return ret;
    }

    // Helper for call() CALL_METHOD_PUT_* methods
    private void callHelperPut(int callingUserId, Uri contentUri, String key, Bundle args) {
        // New value is in the args bundle under the key named by
        // Settings.NameValueTable.VALUE
        final String newValue = (args == null)
                ? null : args.getString(Settings.NameValueTable.VALUE);
        final ContentValues values = new ContentValues();
        values.put(Settings.NameValueTable.NAME, key);
        values.put(Settings.NameValueTable.VALUE, newValue);

        insertForUser(callingUserId, contentUri, values);
    }

    /**
     * Looks up a single value for a specific user, uri, and key.
     * @param userId The id of the user to perform the lookup for.
     * @param uri The uri for which table to perform the lookup in.
     * @param key The key to perform the lookup with.
     * @return A single value stored in a {@link Bundle}.
     */
    private Bundle lookupSingleValue(int userId, Uri uri, String key) {
        Cursor cursor = null;
        try {
            cursor = queryForUser(userId, uri, new String[]{ Settings.NameValueTable.VALUE },
                    Settings.NameValueTable.NAME + " = ?", new String[]{ key }, null);

            if (cursor != null && cursor.getCount() == 1) {
                cursor.moveToFirst();
                String value = cursor.getString(0);
                return value == null ? NULL_SETTING : Bundle.forPair(Settings.NameValueTable.VALUE,
                        value);
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "settings lookup error", e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return NULL_SETTING;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return queryForUser(UserHandle.getCallingUserId(), uri, projection, selection,
                selectionArgs, sortOrder);
    }

    /**
     * Performs a query for a specific user.
     * @param userId The id of the user to perform the query for.
     * @param uri The uri for which table to perform the query on. Optionally, the uri can end in
     *     the name of a specific element to query for.
     * @param projection The columns that are returned in the {@link Cursor}.
     * @param selection The column names that the selection criteria applies to.
     * @param selectionArgs The column values that the selection criteria applies to.
     * @param sortOrder The ordering of how the values should be returned in the {@link Cursor}.
     * @return {@link Cursor} of the results from the query.
     */
    private Cursor queryForUser(int userId, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        int code = sUriMatcher.match(uri);
        String tableName = getTableNameFromUriMatchCode(code);

        LineageDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        queryBuilder.setTables(tableName);

        Cursor returnCursor;
        if (isItemUri(code)) {
            // The uri is looking for an element with a specific name
            returnCursor = queryBuilder.query(db, projection, NAME_SELECTION,
                    new String[] { uri.getLastPathSegment() }, null, null, sortOrder);
        } else {
            returnCursor = queryBuilder.query(db, projection, selection, selectionArgs, null,
                    null, sortOrder);
        }

        return returnCursor;
    }

    @Override
    public String getType(Uri uri) {
        int code = sUriMatcher.match(uri);
        String tableName = getTableNameFromUriMatchCode(code);

        if (isItemUri(code)) {
            return "vnd.android.cursor.item/" + tableName;
        } else {
            return "vnd.android.cursor.dir/" + tableName;
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        return bulkInsertForUser(UserHandle.getCallingUserId(), uri, values);
    }

    /**
     * Performs a bulk insert for a specific user.
     * @param userId The user id to perform the bulk insert for.
     * @param uri The content:// URI of the insertion request.
     * @param values An array of sets of column_name/value pairs to add to the database.
     *    This must not be {@code null}.
     * @return Number of rows inserted.
     */
    int bulkInsertForUser(int userId, Uri uri, ContentValues[] values) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }

        int numRowsAffected = 0;

        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);

        LineageDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        db.beginTransaction();
        try {
            for (ContentValues value : values) {
                if (value == null) {
                    continue;
                }

                long rowId = db.insert(tableName, null, value);

                if (rowId >= 0) {
                    numRowsAffected++;
                } else {
                    return 0;
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (numRowsAffected > 0) {
            notifyChange(uri, tableName, userId);
            if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) inserted");
        }

        return numRowsAffected;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return insertForUser(UserHandle.getCallingUserId(), uri, values);
    }

    /**
     * Performs insert for a specific user.
     * @param userId The user id to perform the insert for.
     * @param uri The content:// URI of the insertion request.
     * @param values A sets of column_name/value pairs to add to the database.
     *    This must not be {@code null}.
     * @return
     */
    private Uri insertForUser(int userId, Uri uri, ContentValues values) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }

        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);

        LineageDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName, userId));

        // Validate value if inserting int System table
        final String name = values.getAsString(Settings.NameValueTable.NAME);
        final String value = values.getAsString(Settings.NameValueTable.VALUE);
        if (LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL.equals(tableName)) {
            validateGlobalSettingNameValue(name, value);
        } else if (LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM.equals(tableName)) {
            validateSystemSettingNameValue(name, value);
        } else if (LineageDatabaseHelper.LineageTableNames.TABLE_SECURE.equals(tableName)) {
            validateSecureSettingValue(name, value);
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = db.insert(tableName, null, values);

        Uri returnUri = null;
        if (rowId > -1) {
            returnUri = Uri.withAppendedPath(uri, name);
            notifyChange(returnUri, tableName, userId);
            if (LOCAL_LOGV) Log.d(TAG, "Inserted row id: " + rowId + " into tableName: " +
                    tableName);
        }

        return returnUri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return deleteForUser(UserHandle.getCallingUserId(), uri, selection, selectionArgs);
    }

    private int deleteForUser(int callingUserId, Uri uri, String selection,
            String[] selectionArgs) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        int numRowsAffected = 0;

        // Allow only selection by key; a null/empty selection string will cause all rows in the
        // table to be deleted
        if (!TextUtils.isEmpty(selection) && selectionArgs.length > 0) {
            String tableName = getTableNameFromUri(uri);
            checkWritePermissions(tableName);

            LineageDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName,
                    callingUserId));

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            numRowsAffected = db.delete(tableName, selection, selectionArgs);

            if (numRowsAffected > 0) {
                notifyChange(uri, tableName, callingUserId);
                if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) deleted");
            }
        }

        return numRowsAffected;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // NOTE: update() is never called by the front-end LineageSettings API, and updates that
        // wind up affecting rows in Secure that are globally shared will not have the
        // intended effect (the update will be invisible to the rest of the system).
        // This should have no practical effect, since writes to the Secure db can only
        // be done by system code, and that code should be using the correct API up front.
        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be null");
        }

        if (values == null) {
            throw new IllegalArgumentException("ContentValues cannot be null");
        }

        String tableName = getTableNameFromUri(uri);
        checkWritePermissions(tableName);

        // Validate value if updating System table
        final String name = values.getAsString(Settings.NameValueTable.NAME);
        final String value = values.getAsString(Settings.NameValueTable.VALUE);
        if (LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL.equals(tableName)) {
            validateGlobalSettingNameValue(name, value);
        } else if (LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM.equals(tableName)) {
            validateSystemSettingNameValue(name, value);
        } else if (LineageDatabaseHelper.LineageTableNames.TABLE_SECURE.equals(tableName)) {
            validateSecureSettingValue(name, value);
        }

        int callingUserId = UserHandle.getCallingUserId();
        LineageDatabaseHelper dbHelper = getOrEstablishDatabase(getUserIdForTable(tableName,
                callingUserId));

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int numRowsAffected = db.update(tableName, values, selection, selectionArgs);

        if (numRowsAffected > 0) {
            notifyChange(uri, tableName, callingUserId);
            if (LOCAL_LOGV) Log.d(TAG, tableName + ": " + numRowsAffected + " row(s) updated");
        }

        return numRowsAffected;
    }

    // endregion Content Provider Methods

    /**
     * Tries to get a {@link LineageDatabaseHelper} for the specified user and if it does not exist, a
     * new instance of {@link LineageDatabaseHelper} is created for the specified user and returned.
     * @param callingUser
     * @return
     */
    private LineageDatabaseHelper getOrEstablishDatabase(int callingUser) {
        if (callingUser >= android.os.Process.SYSTEM_UID) {
            if (USER_CHECK_THROWS) {
                throw new IllegalArgumentException("Uid rather than user handle: " + callingUser);
            } else {
                Log.wtf(TAG, "Establish db for uid rather than user: " + callingUser);
            }
        }

        long oldId = Binder.clearCallingIdentity();
        try {
            LineageDatabaseHelper dbHelper;
            synchronized (this) {
                dbHelper = mDbHelpers.get(callingUser);
            }
            if (null == dbHelper) {
                establishDbTracking(callingUser);
                synchronized (this) {
                    dbHelper = mDbHelpers.get(callingUser);
                }
            }
            return dbHelper;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Check if a {@link LineageDatabaseHelper} exists for a user and if it doesn't, a new helper is
     * created and added to the list of tracked database helpers
     * @param userId
     */
    private void establishDbTracking(int userId) {
        LineageDatabaseHelper dbHelper;

        synchronized (this) {
            dbHelper = mDbHelpers.get(userId);
            if (LOCAL_LOGV) {
                Log.i(TAG, "Checking lineage settings db helper for user " + userId);
            }
            if (dbHelper == null) {
                if (LOCAL_LOGV) {
                    Log.i(TAG, "Installing new lineage settings db helper for user " + userId);
                }
                dbHelper = new LineageDatabaseHelper(getContext(), userId);
                mDbHelpers.append(userId, dbHelper);
            }
        }

        // Initialization of the db *outside* the locks.  It's possible that racing
        // threads might wind up here, the second having read the cache entries
        // written by the first, but that's benign: the SQLite helper implementation
        // manages concurrency itself, and it's important that we not run the db
        // initialization with any of our own locks held, so we're fine.
        dbHelper.getWritableDatabase();
    }

    /**
     * Makes sure the caller has permission to write this data.
     * @param tableName supplied by the caller
     * @throws SecurityException if the caller is forbidden to write.
     */
    private void checkWritePermissions(String tableName) {
        final String callingPackage = getCallingPackage();
        final boolean granted = PackageManager.PERMISSION_GRANTED ==
                getContext().checkCallingOrSelfPermission(
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS);
        final boolean protectedTable =
                LineageDatabaseHelper.LineageTableNames.TABLE_SECURE.equals(tableName) ||
                LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL.equals(tableName);
        // If the permission is granted simply return, no further checks are needed.
        if (granted) {
            return;
        }
        // If the caller doesn't hold WRITE_SECURE_SETTINGS and isn't a protected table,
        // we verify whether this operation is allowed for the calling package through appops.
        if (!protectedTable && Settings.checkAndNoteWriteSettingsOperation(getContext(),
                Binder.getCallingUid(), callingPackage, getCallingAttributionTag(), true)) {
            return;
        }
        throw new SecurityException(
                String.format("Permission denial: writing to lineage settings requires %1$s",
                        lineageos.platform.Manifest.permission.WRITE_SECURE_SETTINGS));
    }

    /**
     * Returns whether the matched uri code refers to an item in a table
     * @param code
     * @return
     */
    private boolean isItemUri(int code) {
        switch (code) {
            case SYSTEM:
            case SECURE:
            case GLOBAL:
                return false;
            case SYSTEM_ITEM_NAME:
            case SECURE_ITEM_NAME:
            case GLOBAL_ITEM_NAME:
                return true;
            default:
                throw new IllegalArgumentException("Invalid uri match code: " + code);
        }
    }

    /**
     * Utilizes an {@link UriMatcher} to check for a valid combination of scheme, authority, and
     * path and returns the corresponding table name
     * @param uri
     * @return Table name
     */
    private String getTableNameFromUri(Uri uri) {
        int code = sUriMatcher.match(uri);

        return getTableNameFromUriMatchCode(code);
    }

    /**
     * Returns the corresponding table name for the matched uri code
     * @param code
     * @return
     */
    private String getTableNameFromUriMatchCode(int code) {
        switch (code) {
            case SYSTEM:
            case SYSTEM_ITEM_NAME:
                return LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM;
            case SECURE:
            case SECURE_ITEM_NAME:
                return LineageDatabaseHelper.LineageTableNames.TABLE_SECURE;
            case GLOBAL:
            case GLOBAL_ITEM_NAME:
                return LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL;
            default:
                throw new IllegalArgumentException("Invalid uri match code: " + code);
        }
    }

    /**
     * If the table is Global, the owner's user id is returned. Otherwise, the original user id
     * is returned.
     * @param tableName
     * @param userId
     * @return User id
     */
    private int getUserIdForTable(String tableName, int userId) {
        return LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL.equals(tableName) ?
                UserHandle.USER_SYSTEM : userId;
    }

    /**
     * Modify setting version for an updated table before notifying of change. The
     * {@link LineageSettings} class uses these to provide client-side caches.
     * @param uri to send notifications for
     * @param userId
     */
    private void notifyChange(Uri uri, String tableName, int userId) {
        String property = null;
        final boolean isGlobal = tableName.equals(LineageDatabaseHelper.LineageTableNames.TABLE_GLOBAL);
        if (tableName.equals(LineageDatabaseHelper.LineageTableNames.TABLE_SYSTEM)) {
            property = LineageSettings.System.SYS_PROP_LINEAGE_SETTING_VERSION;
        } else if (tableName.equals(LineageDatabaseHelper.LineageTableNames.TABLE_SECURE)) {
            property = LineageSettings.Secure.SYS_PROP_LINEAGE_SETTING_VERSION;
        } else if (isGlobal) {
            property = LineageSettings.Global.SYS_PROP_LINEAGE_SETTING_VERSION;
        }

        if (property != null) {
            long version = SystemProperties.getLong(property, 0) + 1;
            if (LOCAL_LOGV) Log.v(TAG, "property: " + property + "=" + version);
            SystemProperties.set(property, Long.toString(version));
        }

        final int notifyTarget = isGlobal ? UserHandle.USER_ALL : userId;
        final long oldId = Binder.clearCallingIdentity();
        try {
            getContext().getContentResolver().notifyChange(uri, null, true, notifyTarget);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
        if (LOCAL_LOGV) Log.v(TAG, "notifying for " + notifyTarget + ": " + uri);
    }

    private void validateGlobalSettingNameValue(String name, String value) {
        LineageSettings.Validator validator = LineageSettings.Global.VALIDATORS.get(name);

        // Not all global settings have validators, but if a validator exists, the validate method
        // should return true
        if (validator != null && !validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }

    private void validateSystemSettingNameValue(String name, String value) {
        LineageSettings.Validator validator = LineageSettings.System.VALIDATORS.get(name);
        if (validator == null) {
            throw new IllegalArgumentException("Invalid setting: " + name);
        }

        if (!validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }

    private void validateSecureSettingValue(String name, String value) {
        LineageSettings.Validator validator = LineageSettings.Secure.VALIDATORS.get(name);

        // Not all secure settings have validators, but if a validator exists, the validate method
        // should return true
        if (validator != null && !validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }

    // TODO Add caching
}
