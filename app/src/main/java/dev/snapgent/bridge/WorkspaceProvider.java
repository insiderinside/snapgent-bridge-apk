package dev.snapgent.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Read-only bridge from the app-private workspace to the rest of Android, so the Files page can
 * hand a voice-over or an image to another app (Open with / Share) without adding a dependency on
 * androidx FileProvider.
 *
 * <p>Only files inside {@code getFilesDir()} are exposed, the authority is not exported, and every
 * URI has to be granted explicitly by the Intent that carries it.
 */
public class WorkspaceProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.snapgent.bridge.files";

    @Override public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException("no path");
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) throw new FileNotFoundException("no path");
        if (getContext() == null) throw new FileNotFoundException("no context");
        File root;
        try {
            root = getContext().getFilesDir().getCanonicalFile();
        } catch (Exception e) {
            throw new FileNotFoundException("workspace unavailable");
        }
        File target;
        try {
            target = new File(root, path).getCanonicalFile();
        } catch (Exception e) {
            throw new FileNotFoundException("bad path");
        }
        String rootPath = root.getPath();
        if (!target.getPath().equals(rootPath) && !target.getPath().startsWith(rootPath + File.separator)) {
            throw new FileNotFoundException("outside workspace");
        }
        if (!target.isFile()) throw new FileNotFoundException("not a file");
        return target;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.US));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}
