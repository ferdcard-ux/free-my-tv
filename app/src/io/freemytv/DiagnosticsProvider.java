package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import java.io.IOException;
import java.util.List;

/** Read-only diagnostics interface for adb shell; app UIDs cannot read through this provider. */
public final class DiagnosticsProvider extends ContentProvider {
    private static final int SHELL_UID = 2000;
    private static final String[] COLUMNS = {"time", "epoch_ms", "level", "process", "pid", "message"};

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb shell may read diagnostics");
        if (uri == null || uri.getPathSegments().size() != 1 || !"logs".equals(uri.getLastPathSegment())) {
            throw new IllegalArgumentException("use content://io.freemytv.diagnostics/logs");
        }
        int limit = integer(uri, "limit", 1000);
        long since = longValue(uri, "since", 0);
        if (limit < 1 || since < 0) throw new IllegalArgumentException("limit must be positive and since non-negative");
        try {
            List<String[]> rows = AppLog.read(getContext(), limit, since);
            MatrixCursor cursor = new MatrixCursor(COLUMNS, rows.size());
            for (String[] row : rows) cursor.addRow(row);
            return cursor;
        } catch (IOException e) {
            throw new IllegalStateException("couldn't read diagnostics", e);
        }
    }

    private static int integer(Uri uri, String key, int fallback) {
        String value = uri.getQueryParameter(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + key, e);
        }
    }

    private static long longValue(Uri uri, String key, long fallback) {
        String value = uri.getQueryParameter(key);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + key, e);
        }
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.io.freemytv.diagnostic";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("diagnostics are read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("diagnostics are read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("diagnostics are read-only");
    }
}
