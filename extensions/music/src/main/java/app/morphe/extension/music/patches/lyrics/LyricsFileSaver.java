/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Saves raw lyrics text to the device's Downloads directory via MediaStore.
 * No storage permissions required on API 29+.
 */
public final class LyricsFileSaver {

    private static final String TAG = "MorpheLyrics";

    private LyricsFileSaver() {
    }

    @Nullable
    public static String save(Context context, TrackInfo track, Lyrics lyrics) {
        if (lyrics.rawFormat() == null || lyrics.rawFormat().isEmpty()) {
            Log.w(TAG, "save: rawFormat is null or empty");
            return null;
        }
        if (lyrics.formatType() == null || lyrics.formatType().isEmpty()) {
            Log.w(TAG, "save: formatType is null or empty");
            return null;
        }

        final String fileName = sanitizeFileName(track.artist() + " - " + track.title())
                + "." + lyrics.formatType();

        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(lyrics.formatType()));
        final String directoryName = ResourceUtils.getString("morphe_custom_branding_name_entry_2");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + directoryName);

        // Avoid overwriting existing files by appending a timestamp if needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        final Uri insertUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (insertUri == null) {
            Log.e(TAG, "save: MediaStore insert returned null");
            return null;
        }

        try (OutputStream out = resolver.openOutputStream(insertUri)) {
            if (out == null) {
                Log.e(TAG, "save: openOutputStream returned null");
                resolver.delete(insertUri, null, null);
                return null;
            }
            out.write(lyrics.rawFormat().getBytes(StandardCharsets.UTF_8));
            out.flush();
            Log.i(TAG, "save: saved to " + insertUri);
            return Environment.DIRECTORY_DOWNLOADS + "/" + directoryName + "/" + fileName;
        } catch (Exception ex) {
            Log.e(TAG, "save: write failed", ex);
            resolver.delete(insertUri, null, null);
            return null;
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(insertUri, values, null, null);
            }
        }
    }

    private static String sanitizeFileName(String name) {
        // Remove characters illegal in file names on most filesystems.
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }

    private static String getMimeType(String formatType) {
        switch (formatType) {
            case "lrc":
                return "application/octet-stream";
            case "krc":
                return "application/octet-stream";
            case "yrc":
                return "application/octet-stream";
            case "qrc":
                return "application/octet-stream";
            case "ttml":
                return "application/ttml+xml";
            case "lyricsfile.yaml":
                return "text/yaml";
            case "json":
                return "application/json";
            case "json3":
                return "application/json";
            case "mxm.json":
                return "application/json";
            case "sp.json":
                return "application/json";
            case "plain":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }
}