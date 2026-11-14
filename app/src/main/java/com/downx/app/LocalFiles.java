package com.downx.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * 极简文件通道：把 App 私有目录里的视频/图片以 content:// 暴露给系统播放器（临时授权读）。
 * 不用 androidx，也不 export，只有拿到授权的应用能读。
 */
public class LocalFiles extends ContentProvider {

    // authority 绑定宿主应用，避免与已安装的原版 DownX 产生 provider 冲突
    public static final String AUTH = "com.example.immersive.downx.files";

    public static Uri uriFor(Context c, File f) {
        return Uri.parse("content://" + AUTH + "/" + Uri.encode(f.getAbsolutePath()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p == null) return "*/*";
        p = Uri.decode(p).toLowerCase();
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        return "video/mp4";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (Exception e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = f.getName();
        }
        c.addRow(row);
        return c;
    }

    private File resolve(Uri uri) throws java.io.FileNotFoundException {
        String seg = uri.getLastPathSegment();
        if (seg == null) throw new java.io.FileNotFoundException("empty");
        File f = new File(Uri.decode(seg));
        String path = f.getAbsolutePath();
        Context c = getContext();
        boolean allowed = path.contains("/DownX/");
        if (!allowed && c != null) {
            File ext = c.getExternalFilesDir(null);
            if (ext != null && path.startsWith(ext.getAbsolutePath())) allowed = true;
            if (path.startsWith(c.getFilesDir().getAbsolutePath())) allowed = true;
        }
        if (!allowed || !f.exists()) throw new java.io.FileNotFoundException(path);
        return f;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
