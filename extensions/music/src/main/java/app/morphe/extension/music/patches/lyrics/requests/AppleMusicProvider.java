/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;

public final class AppleMusicProvider implements LyricsProvider {

    private static final String TAG = "AppleMusic";

    private static final String BROWSE_URL = "https://music.apple.com";
    private static final String API_BASE = "https://amp-api.music.apple.com/v1/catalog/";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern JS_BUNDLE =
            Pattern.compile("/assets/index~[^/\"'\\s]+\\.js");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+");

    private static final Object TOKEN_LOCK = new Object();
    @Nullable
    private static String cachedDevToken;
    @Nullable
    private static String cachedStorefront;
    @Nullable
    private static String cachedLanguage;

    @Override
    public String name() {
        return "AppleMusic";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        Log.d(TAG, "AppleMusic: fetch '" + track.title() + "' by '" + track.artist() + "'");
        final String userToken = Settings.APPLE_MUSIC_TOKEN.get();
        if (userToken.isEmpty()) {
            Log.d(TAG, "AppleMusic: no media-user-token configured");
            return null;
        }

        synchronized (TOKEN_LOCK) {
            if (cachedDevToken == null) {
                Log.d(TAG, "AppleMusic: fetching dev token...");
                cachedDevToken = fetchDevToken();
                cachedStorefront = null;
                cachedLanguage = null;
            }
            if (cachedDevToken == null) {
                Log.d(TAG, "AppleMusic: dev token fetch failed, aborting");
                return null;
            }
            Log.d(TAG, "AppleMusic: dev token OK (length=" + cachedDevToken.length() + ")");
            if (cachedStorefront == null) {
                Log.d(TAG, "AppleMusic: resolving storefront...");
                resolveStorefront(userToken);
            }
        }

        final String storefront = cachedStorefront != null ? cachedStorefront : "us";
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";
        Log.d(TAG, "AppleMusic: using storefront=" + storefront + " language=" + language);

        final String songId = searchSong(track, userToken, storefront);
        if (songId == null) {
            Log.d(TAG, "AppleMusic: no song found for '" + track.title() + "'");
            return null;
        }
        Log.d(TAG, "AppleMusic: found songId=" + songId);

        return fetchLyrics(userToken, storefront, language, songId);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        Log.d(TAG, "AppleMusic: fetchCandidates '" + track.title() + "' by '" + track.artist() + "'");
        final String userToken = Settings.APPLE_MUSIC_TOKEN.get();
        if (userToken.isEmpty()) {
            return new ArrayList<>();
        }

        synchronized (TOKEN_LOCK) {
            if (cachedDevToken == null) {
                Log.d(TAG, "AppleMusic: fetchCandidates fetching dev token...");
                cachedDevToken = fetchDevToken();
                cachedStorefront = null;
                cachedLanguage = null;
            }
            if (cachedDevToken == null) {
                Log.d(TAG, "AppleMusic: fetchCandidates dev token failed");
                return new ArrayList<>();
            }
            if (cachedStorefront == null) {
                resolveStorefront(userToken);
            }
        }

        final String storefront = cachedStorefront != null ? cachedStorefront : "us";
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";
        Log.d(TAG, "AppleMusic: fetchCandidates storefront=" + storefront + " language=" + language);

        final List<String> songIds = searchAllSongs(track, userToken, storefront);
        if (songIds.isEmpty()) {
            Log.d(TAG, "AppleMusic: fetchCandidates no songs found");
            return new ArrayList<>();
        }
        Log.d(TAG, "AppleMusic: fetchCandidates found " + songIds.size() + " song ids: " + songIds);

        List<Lyrics> results = new ArrayList<>();
        for (String songId : songIds) {
            if (results.size() >= 5) {
                break;
            }
            try {
                Log.d(TAG, "AppleMusic: fetchCandidates fetching lyrics for songId=" + songId);
                Lyrics lyrics = fetchLyrics(userToken, storefront, language, songId);
                if (lyrics != null) {
                    Log.d(TAG, "AppleMusic: fetchCandidates got lyrics for songId=" + songId);
                    results.add(lyrics);
                } else {
                    Log.d(TAG, "AppleMusic: fetchCandidates no lyrics for songId=" + songId);
                }
            } catch (Exception ex) {
                Log.d(TAG, "AppleMusic candidate failed: " + ex.getMessage());
            }
        }
        Log.d(TAG, "AppleMusic: fetchCandidates returning " + results.size() + " results");
        return results;
    }

    private List<String> searchAllSongs(TrackInfo track, String userToken, String storefront) {
        HttpURLConnection connection = null;
        try {
            final String term = LyricsRequests.encode(track.title() + " " + track.artist());
            final String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            Log.d(TAG, "AppleMusic: searchAllSongs URL: " + url);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            Log.d(TAG, "AppleMusic: searchAllSongs HTTP " + code);
            if (code != 200) {
                return new ArrayList<>();
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONObject results = root.optJSONObject("results");
            if (results == null) {
                Log.d(TAG, "AppleMusic: searchAllSongs no results object");
                return new ArrayList<>();
            }
            final JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                Log.d(TAG, "AppleMusic: searchAllSongs no songs object");
                return new ArrayList<>();
            }
            final JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                Log.d(TAG, "AppleMusic: searchAllSongs no items");
                return new ArrayList<>();
            }
            Log.d(TAG, "AppleMusic: searchAllSongs got " + items.length() + " results");

            List<String> ids = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    final String id = item.optString("id", null);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: searchAllSongs failed", ex);
            return new ArrayList<>();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean validateToken(String userToken) {
        synchronized (TOKEN_LOCK) {
            if (cachedDevToken == null) {
                cachedDevToken = fetchDevToken();
            }
        }
        if (cachedDevToken == null) {
            Log.d(TAG, "AppleMusic: validateToken failed — no dev token");
            return false;
        }

        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/me/storefront", userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "AppleMusic: validateToken failed — HTTP " + code);
                return false;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            return data != null && data.length() > 0;
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: validateToken failed", ex);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static String fetchDevToken() {
        try {
            Log.d(TAG, "AppleMusic: fetching browse page " + BROWSE_URL);
            final HttpURLConnection browseConn = LyricsRequests.openConnection(BROWSE_URL);
            browseConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            final int browseCode = browseConn.getResponseCode();
            Log.d(TAG, "AppleMusic: browse page HTTP " + browseCode);
            if (browseCode != 200) {
                Log.d(TAG, "AppleMusic: browse page failed");
                return null;
            }
            final String html = LyricsRequests.parseGzipString(browseConn);
            Log.d(TAG, "AppleMusic: browse page length=" + (html != null ? html.length() : 0));

            final Matcher jsMatcher = JS_BUNDLE.matcher(html);
            if (jsMatcher.find()) {
                final String jsPath = jsMatcher.group(0);
                Log.d(TAG, "AppleMusic: JS bundle path: " + jsPath);
                final String js = fetchJsBundle(BROWSE_URL + jsPath);
                if (js != null) {
                    Log.d(TAG, "AppleMusic: JS bundle length=" + js.length());
                    final String token = extractJwt(js);
                    if (token != null) {
                        Log.d(TAG, "AppleMusic: dev token from JS bundle, length=" + token.length());
                        return token;
                    }
                }
            }

            Log.d(TAG, "AppleMusic: could not extract developer token");
            return null;
        } catch (IOException ex) {
            Log.d(TAG, "AppleMusic: failed to fetch developer token", ex);
            return null;
        }
    }

    @Nullable
    private static String fetchJsBundle(String jsUrl) {
        try {
            final HttpURLConnection jsConn = LyricsRequests.openConnection(jsUrl);
            jsConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            return LyricsRequests.parseGzipString(jsConn);
        } catch (IOException ex) {
            Log.d(TAG, "AppleMusic: failed to fetch JS bundle: " + jsUrl, ex);
            return null;
        }
    }

    @Nullable
    private static String extractJwt(String text) {
        if (text == null) {
            return null;
        }
        final Matcher m = JWT.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    private void resolveStorefront(String userToken) {
        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/me/storefront", userToken);
            final int code = connection.getResponseCode();
            Log.d(TAG, "AppleMusic: storefront API HTTP " + code);
            if (code == 200) {
                final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
                final JSONArray data = root.optJSONArray("data");
                Log.d(TAG, "AppleMusic: storefront API data=" + (data != null ? data.length() + " items" : "null"));
                if (data != null && data.length() > 0) {
                    final JSONObject storefront = data.optJSONObject(0);
                    if (storefront != null) {
                        cachedStorefront = LyricsRequests.optString(storefront, "id");
                        final JSONObject attributes = storefront.optJSONObject("attributes");
                        if (attributes != null) {
                            final String lang = LyricsRequests.optString(attributes, "defaultLanguageTag");
                            if (lang != null) {
                                cachedLanguage = lang;
                            }
                        }
                        Log.d(TAG, "AppleMusic: resolved storefront=" + cachedStorefront + " language=" + cachedLanguage);
                    }
                }
            } else {
                Log.d(TAG, "AppleMusic: storefront API failed, will use locale fallback");
            }
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: failed to resolve storefront", ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (cachedStorefront == null) {
            final Locale sysLocale = Locale.getDefault();
            final String country = sysLocale.getCountry();
            if (country != null && !country.isEmpty()) {
                cachedStorefront = country.toLowerCase(Locale.ROOT);
                Log.d(TAG, "AppleMusic: using system locale storefront: " + cachedStorefront);
            } else {
                Log.d(TAG, "AppleMusic: system locale has no country, falling back to 'us'");
                cachedStorefront = "us";
            }
            if (cachedLanguage == null) {
                final String lang = sysLocale.getLanguage();
                if (lang != null && !lang.isEmpty()) {
                    cachedLanguage = lang;
                }
            }
        }
    }

    @Nullable
    private String searchSong(TrackInfo track, String userToken, String storefront) {
        HttpURLConnection connection = null;
        try {
            final String term = LyricsRequests.encode(track.title() + " " + track.artist());
            final String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            Log.d(TAG, "AppleMusic: searchSong URL: " + url);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            Log.d(TAG, "AppleMusic: searchSong HTTP " + code);
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONObject results = root.optJSONObject("results");
            if (results == null) {
                Log.d(TAG, "AppleMusic: searchSong no results object");
                return null;
            }
            final JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                Log.d(TAG, "AppleMusic: searchSong no songs object");
                return null;
            }
            final JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                Log.d(TAG, "AppleMusic: searchSong no items");
                return null;
            }
            Log.d(TAG, "AppleMusic: searchSong got " + items.length() + " results");

            final String title = track.title().toLowerCase().trim();
            final String artist = track.artist().toLowerCase().trim();
            String bestId = null;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final JSONObject attributes = item.optJSONObject("attributes");
                if (attributes == null) {
                    continue;
                }
                final String itemTitle = attributes.optString("name", "");
                final String itemArtist = attributes.optString("artistName", "");
                final int idx = i;
                final String itemId = item.optString("id", null);
                Log.d(TAG, "AppleMusic: searchSong result[" + idx + "] id=" + itemId
                        + " title='" + itemTitle + "' artist='" + itemArtist + "'");
                if (bestId == null) {
                    bestId = itemId;
                }
                if (itemTitle.toLowerCase().contains(title) && itemArtist.toLowerCase().contains(artist)) {
                    bestId = itemId;
                    final String matchId = bestId;
                    Log.d(TAG, "AppleMusic: searchSong exact match id=" + matchId);
                    break;
                }
            }
            final String resultId = bestId;
            Log.d(TAG, "AppleMusic: searchSong bestId=" + resultId);
            return bestId;
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: searchSong failed", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchLyrics(String userToken, String storefront, String language, String songId) {
        Log.d(TAG, "AppleMusic: trying dedicated lyrics for songId=" + songId);
        final Lyrics dedicatedResult = fetchLyricsDedicated(userToken, storefront, language, songId);
        final int dedicatedRank = rankOfLyrics(dedicatedResult);
        Log.d(TAG, "AppleMusic: dedicated rank=" + dedicatedRank);

        Log.d(TAG, "AppleMusic: trying include lyrics for songId=" + songId);
        final Lyrics includeResult = fetchLyricsInclude(userToken, storefront, language, songId);
        final int includeRank = rankOfLyrics(includeResult);
        Log.d(TAG, "AppleMusic: include rank=" + includeRank);

        if (includeRank > dedicatedRank) {
            Log.d(TAG, "AppleMusic: include has better quality, using include result");
            return includeResult;
        }
        if (dedicatedResult != null) {
            Log.d(TAG, "AppleMusic: using dedicated result");
            return dedicatedResult;
        }
        Log.d(TAG, "AppleMusic: both lyrics endpoints failed for songId=" + songId);
        return includeResult;
    }

    private static int rankOfLyrics(@Nullable Lyrics lyrics) {
        if (lyrics == null) {
            return 0;
        }
        for (LyricsLine line : lyrics.lines()) {
            if (line.hasWords()) {
                return 2; // word-level
            }
        }
        return lyrics.synced() ? 1 : 0;
    }

    @Nullable
    private Lyrics fetchLyricsDedicated(String userToken, String storefront, String language,
                                         String songId) {
        HttpURLConnection connection = null;
        try {
            final String url = API_BASE + storefront + "/songs/" + songId
                    + "/lyrics?l=" + LyricsRequests.encode(language) + "&extend=ttmlLocalizations";
            Log.d(TAG, "AppleMusic: dedicated URL: " + url);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            Log.d(TAG, "AppleMusic: dedicated HTTP " + code);
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                Log.d(TAG, "AppleMusic: dedicated no data array");
                return null;
            }
            final JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            final JSONObject attributes = song.optJSONObject("attributes");
            if (attributes == null) {
                Log.d(TAG, "AppleMusic: dedicated no attributes");
                return null;
            }
            String ttml = null;
            if (attributes.has("ttmlLocalizations")) {
                final JSONObject localizations = attributes.optJSONObject("ttmlLocalizations");
                if (localizations != null) {
                    ttml = LyricsRequests.optString(localizations, language);
                    if (ttml == null || ttml.isEmpty()) {
                        final String langCode = language.contains("-")
                                ? language.split("-")[0] : language;
                        ttml = LyricsRequests.optString(localizations, langCode);
                    }
                    if (ttml == null || ttml.isEmpty()) {
                        ttml = LyricsRequests.optString(localizations, "en-US");
                    }
                }
            }
            if (ttml == null || ttml.isEmpty()) {
                ttml = LyricsRequests.optString(attributes, "ttml");
            }
            if (ttml == null || ttml.isEmpty()) {
                Log.d(TAG, "AppleMusic: dedicated no ttml field");
                return null;
            }
            Log.d(TAG, "AppleMusic: dedicated ttml length=" + ttml.length());
            final TtmlParser.TtmlResult result = TtmlParser.parse(ttml);
            if (result == null || result.lines.isEmpty()) {
                Log.d(TAG, "AppleMusic: dedicated TTML parse returned no lines");
                return null;
            }
            Log.d(TAG, "AppleMusic: dedicated got " + result.lines.size() + " lines");
            return new Lyrics(result.lines, name(), true,
                    result.romanization, result.translations);
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: dedicated lyrics fetch failed", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchLyricsInclude(String userToken, String storefront, String language,
                                       String songId) {
        HttpURLConnection connection = null;
        try {
            final String url = API_BASE + storefront + "/songs/" + songId
                    + "?include[songs]=albums,lyrics,syllable-lyrics&l=" + LyricsRequests.encode(language);
            Log.d(TAG, "AppleMusic: include URL: " + url);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            Log.d(TAG, "AppleMusic: include HTTP " + code);
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                Log.d(TAG, "AppleMusic: include no data array");
                return null;
            }
            final JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            final JSONObject relationships = song.optJSONObject("relationships");
            if (relationships == null) {
                Log.d(TAG, "AppleMusic: include no relationships");
                return null;
            }
            String ttml = null;
            final JSONObject syllableLyrics = relationships.optJSONObject("syllable-lyrics");
            if (syllableLyrics != null) {
                final JSONArray syllableData = syllableLyrics.optJSONArray("data");
                if (syllableData != null && syllableData.length() > 0) {
                    final JSONObject syllable = syllableData.optJSONObject(0);
                    if (syllable != null) {
                        final JSONObject syllableAttrs = syllable.optJSONObject("attributes");
                        if (syllableAttrs != null) {
                            ttml = LyricsRequests.optString(syllableAttrs, "ttml");
                            if (ttml != null && !ttml.isEmpty()) {
                                Log.d(TAG, "AppleMusic: include got syllable-lyrics ttml");
                            }
                        }
                    }
                }
            }
            if (ttml == null || ttml.isEmpty()) {
                final JSONObject lyrics = relationships.optJSONObject("lyrics");
                if (lyrics != null) {
                    final JSONArray lyricsData = lyrics.optJSONArray("data");
                    if (lyricsData != null && lyricsData.length() > 0) {
                        final JSONObject lyric = lyricsData.optJSONObject(0);
                        if (lyric != null) {
                            final JSONObject lyricAttrs = lyric.optJSONObject("attributes");
                            if (lyricAttrs != null) {
                                ttml = LyricsRequests.optString(lyricAttrs, "ttml");
                            }
                        }
                    }
                }
            }
            if (ttml == null || ttml.isEmpty()) {
                Log.d(TAG, "AppleMusic: include no ttml from lyrics or syllable-lyrics");
                return null;
            }
            Log.d(TAG, "AppleMusic: include ttml length=" + ttml.length());
            final TtmlParser.TtmlResult result = TtmlParser.parse(ttml);
            if (result == null || result.lines.isEmpty()) {
                Log.d(TAG, "AppleMusic: include TTML parse returned no lines");
                return null;
            }
            Log.d(TAG, "AppleMusic: include got " + result.lines.size() + " lines");
            return new Lyrics(result.lines, name(), true,
                    result.romanization, result.translations);
        } catch (Exception ex) {
            Log.d(TAG, "AppleMusic: include lyrics fetch failed", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openApi(String url, String userToken) throws IOException {
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";
        connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
        connection.setRequestProperty("Authorization", "Bearer " + cachedDevToken);
        connection.setRequestProperty("media-user-token", userToken);
        connection.setRequestProperty("content-type", "application/json;charset=utf-8");
        connection.setRequestProperty("origin", "https://music.apple.com");
        connection.setRequestProperty("referer", "https://music.apple.com/");
        connection.setRequestProperty("accept", "application/json");
        connection.setRequestProperty("accept-encoding", "gzip, deflate");
        connection.setRequestProperty("accept-language", language + ",en;q=0.9");
        connection.setRequestProperty("Cookie", "media-user-token=" + userToken);
        return connection;
    }
}
