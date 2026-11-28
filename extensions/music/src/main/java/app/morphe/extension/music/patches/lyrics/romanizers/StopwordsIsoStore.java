/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.LyricsDirectories;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Downloadable per-language stopword table from stopwords-iso (MIT). */
public final class StopwordsIsoStore {

    private static final String FILE = "stopwords-iso.json";
    private static final String NAME = "Stopwords ISO";
    private static final String URL =
            "https://raw.githubusercontent.com/stopwords-iso/stopwords-iso/master/" + FILE;

    private static volatile Map<String, Set<String>> parsed;

    private StopwordsIsoStore() {
    }

    @Nullable
    public static File file() {
        File dir = LyricsDirectories.directory(LyricsDirectories.WORD_DICT);
        return dir == null ? null : new File(dir, FILE);
    }

    public static boolean isReady() {
        File f = file();
        return f != null && f.exists() && parsed() != null;
    }

    public static void ensure(@NonNull RomanizationDictionary.Listener listener) {
        if (isReady()) {
            listener.onDownload(NAME, 100);
            listener.onReady(size());
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                File dir = LyricsDirectories.directory(LyricsDirectories.WORD_DICT);
                if (dir == null) {
                    throw new IOException("no dictionary directory");
                }
                File target = new File(dir, FILE);
                if (!target.exists()) {
                    listener.onDownload(NAME, 0);
                    RomanizationDictionary.download(URL, target, NAME, listener);
                } else {
                    listener.onDownload(NAME, 100);
                }
                Map<String, Set<String>> map = read(target);
                parsed = map;
                listener.onReady(size(map));
            } catch (Throwable ex) {
                Logger.printDebug(() -> "Could not load stopwords-iso",
                        ex instanceof Exception e ? e : new RuntimeException(ex));
                listener.onFailed();
            }
        });
    }

    public static void delete() {
        parsed = null;
        File f = file();
        if (f != null && f.exists()) {
            Utils.deleteQuietly(f);
        }
    }

    @NonNull
    public static Set<String> forLanguage(@Nullable String language) {
        Map<String, Set<String>> map = parsed();
        if (map == null || language == null || language.trim().isEmpty()) {
            return Collections.emptySet();
        }
        String code = language.toLowerCase(Locale.ROOT).split("-", 2)[0];
        Set<String> words = map.get(code);
        return words == null ? Collections.emptySet() : words;
    }

    @NonNull
    public static Set<String> languages() {
        Map<String, Set<String>> map = parsed();
        return map == null ? Collections.emptySet() : map.keySet();
    }

    @NonNull
    public static Set<String> stopwordsFor(@Nullable String language) {
        return forLanguage(language);
    }

    public static int size() {
        return size(parsed());
    }

    private static int size(@Nullable Map<String, Set<String>> map) {
        if (map == null) {
            return 0;
        }
        int n = 0;
        for (Set<String> set : map.values()) {
            n += set.size();
        }
        return n;
    }

    @Nullable
    private static Map<String, Set<String>> parsed() {
        Map<String, Set<String>> local = parsed;
        if (local != null) {
            return local;
        }
        File f = file();
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            local = read(f);
            parsed = local;
            return local;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse stopwords-iso", ex);
            return null;
        }
    }

    @NonNull
    private static Map<String, Set<String>> read(File file) throws Exception {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
        Map<String, Set<String>> out = new HashMap<>();
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Set<String> words = new HashSet<>();
            for (int i = 0; i < root.getJSONArray(key).length(); i++) {
                String word = root.getJSONArray(key).getString(i)
                        .toLowerCase(Locale.ROOT).trim();
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
            out.put(key, Collections.unmodifiableSet(words));
        }
        return Collections.unmodifiableMap(out);
    }
}
