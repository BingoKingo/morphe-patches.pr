/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.requests.Requester;

public final class AmllProvider implements LyricsProvider {

    private static final String BASE_URL = "https://api.amll.dev/v1/lyrics";

    @Override
    public String name() {
        return "AMLL";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        // 1) Resolve the AMLL lyric id from the track metadata.
        final StringBuilder searchUrl = new StringBuilder(BASE_URL);
        searchUrl.append("/search?musicName=").append(LyricsRequests.encode(track.title()));
        searchUrl.append("&artistName=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            searchUrl.append("&albumName=").append(LyricsRequests.encode(track.album()));
        }

        HttpURLConnection searchConnection = null;
        long lyricId = -1;
        try {
            searchConnection = LyricsRequests.openConnection(searchUrl.toString());
            if (searchConnection.getResponseCode() != 200) {
                LyricsRequests.logFailure(name(), searchConnection);
                return null;
            }
            final JSONObject searchRoot = Requester.parseJSONObject(searchConnection);
            final JSONObject searchData = searchRoot.optJSONObject("data");
            if (searchData == null) {
                return null;
            }
            final JSONArray items = searchData.optJSONArray("items");
            if (items == null || items.length() == 0) {
                return null;
            }
            final JSONObject best = items.optJSONObject(0);
            if (best == null) {
                return null;
            }
            lyricId = best.optLong("id", -1);
            if (lyricId < 0) {
                return null;
            }
        } finally {
            if (searchConnection != null) {
                searchConnection.disconnect();
            }
        }

        // 2) Fetch the TTML for that id. Only the ttml format is served, which gives us
        // word-level timing; the LyricsManager falls back to line sync if a line lacks words.
        HttpURLConnection getConnection = null;
        try {
            getConnection = LyricsRequests.openConnection(
                    BASE_URL + "/get?id=" + lyricId + "&format=ttml");
            if (getConnection.getResponseCode() != 200) {
                LyricsRequests.logFailure(name(), getConnection);
                return null;
            }
            final JSONObject getRoot = Requester.parseJSONObject(getConnection);
            final JSONObject getData = getRoot.optJSONObject("data");
            if (getData == null) {
                return null;
            }
            final String ttml = LyricsRequests.optString(getData, "lyrics");
            if (ttml == null) {
                return null;
            }
            final TtmlParser.TtmlResult result = TtmlParser.parse(ttml);
            if (result == null || result.lines.isEmpty()) {
                return null;
            }
            return new Lyrics(result.lines, name(), true, result.romanization, result.translations,
                    result.romanizations, result.songwriters, ttml, "ttml", null);
        } finally {
            if (getConnection != null) {
                getConnection.disconnect();
            }
        }
    }
}
