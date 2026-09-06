package app.morphe.extension.music.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.migrateOldSettingToNew;
import static app.morphe.extension.shared.settings.Setting.parent;
import static app.morphe.extension.shared.settings.Setting.parentNot;
import static app.morphe.extension.shared.settings.Setting.parentsAll;
import static app.morphe.extension.shared.settings.Setting.parentsAny;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.IGNORE;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.SKIP_AUTOMATICALLY;

import app.morphe.extension.music.patches.ChangeHeaderPatch.HeaderLogo;
import app.morphe.extension.music.patches.ChangeStartPagePatch.StartPage;
import app.morphe.extension.music.patches.CrossfadeManager.CrossFadeDuration;
import app.morphe.extension.music.patches.CrossfadeManager.FadeCurve;
import app.morphe.extension.music.sponsorblock.MusicSponsorBlockConfig;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.spoof.ClientType;


@SuppressWarnings({"deprecation", "RedundantSuppression"})
public class Settings extends SharedYouTubeSettings {

    // Ads
    public static final BooleanSetting HIDE_GET_PREMIUM_LABEL = new BooleanSetting("morphe_music_hide_get_premium_label", TRUE, true);
    public static final BooleanSetting HIDE_MUSIC_PREMIUM_PROMOTIONS = new BooleanSetting("morphe_music_hide_music_premium_promotions", TRUE, true);
    public static final BooleanSetting HIDE_VIDEO_ADS = new BooleanSetting("morphe_music_hide_video_ads", TRUE, true);

    // Feed
    public static final BooleanSetting HIDE_EXPLORE_SHELF = new BooleanSetting("morphe_music_hide_explore_shelf", FALSE, true);
    public static final BooleanSetting HIDE_GRID_SHELVES = new BooleanSetting("morphe_music_hide_grid_shelves", FALSE, true);
    public static final BooleanSetting HIDE_HORIZONTAL_SHELVES = new BooleanSetting("morphe_music_hide_horizontal_shelves", FALSE, true);
    public static final BooleanSetting HIDE_LIST_SHELVES = new BooleanSetting("morphe_music_hide_list_shelves", FALSE, true);
    public static final BooleanSetting HIDE_NEW_FROM_SHELF = new BooleanSetting("morphe_music_hide_new_from_shelf", FALSE, true);
    public static final BooleanSetting HIDE_PLAYLIST_SHELVES = new BooleanSetting("morphe_music_hide_playlist_shelves", FALSE, true);
    public static final BooleanSetting HIDE_SPEED_DIAL_SHELF = new BooleanSetting("morphe_music_hide_speed_dial_shelf", FALSE, true);

    // General (Layout)
    public static final EnumSetting<StartPage> CHANGE_START_PAGE = new EnumSetting<>("morphe_change_start_page", StartPage.DEFAULT, true);
    public static final BooleanSetting HIDE_CAST_BUTTON = new BooleanSetting("morphe_music_hide_cast_button", TRUE, true);
    public static final BooleanSetting HIDE_FILTER_BAR = new BooleanSetting("morphe_music_hide_filter_bar", FALSE, true);
    public static final BooleanSetting HIDE_HISTORY_BUTTON = new BooleanSetting("morphe_music_hide_history_button", FALSE, true);
    public static final BooleanSetting HIDE_SEARCH_BUTTON = new BooleanSetting("morphe_music_hide_search_button", FALSE, true);
    public static final BooleanSetting HIDE_NOTIFICATION_BUTTON = new BooleanSetting("morphe_music_hide_notification_button", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR = new BooleanSetting("morphe_music_hide_navigation_bar", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR_HOME_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_home_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_SAMPLES_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_samples_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_EXPLORE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_explore_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LIBRARY_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_library_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_UPGRADE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_upgrade_button", TRUE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LABEL = new BooleanSetting("morphe_music_hide_navigation_bar_labels", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final EnumSetting<HeaderLogo> HEADER_LOGO = new EnumSetting<>("morphe_header_logo", HeaderLogo.DEFAULT, true);


    // Custom filter
    public static final BooleanSetting CUSTOM_FILTER = new BooleanSetting("morphe_music_custom_filter", FALSE);
    public static final StringSetting CUSTOM_FILTER_STRINGS = new StringSetting("morphe_music_custom_filter_strings", "", true, parent(CUSTOM_FILTER));

    // Settings menu filter
    public static final StringSetting SETTINGS_MENU_FILTER_STRINGS = new StringSetting("morphe_music_settings_menu_filter_strings", "", true);
    public static final StringSetting SETTINGS_MENU_FILTER_DISCOVERED = new StringSetting("morphe_music_settings_menu_filter_discovered", "", true, false);

    // Player
    public static final BooleanSetting CHANGE_MINIPLAYER_COLOR = new BooleanSetting("morphe_music_change_miniplayer_color", FALSE, true);
    public static final BooleanSetting CHANGE_NAVIGATION_BAR_COLOR = new BooleanSetting("morphe_music_change_navigation_bar_color", FALSE, true, parent(CHANGE_MINIPLAYER_COLOR));
    public static final BooleanSetting DISABLE_DISLIKE_REDIRECTION = new BooleanSetting("morphe_music_disable_dislike_redirection", FALSE, true);
    public static final BooleanSetting ENABLE_FORCED_MINIPLAYER = new BooleanSetting("morphe_music_enable_forced_miniplayer", FALSE, true);
    public static final BooleanSetting ENABLE_SWIPE_TO_DISMISS_MINIPLAYER = new BooleanSetting("morphe_music_enable_swipe_to_dismiss_miniplayer", FALSE, true);
    public static final BooleanSetting HIDE_AUDIO_VIDEO_TOGGLE = new BooleanSetting("morphe_music_hide_audio_video_toggle", FALSE, true);
    public static final BooleanSetting HIDE_LYRICS_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_share_button", FALSE, true);
    public static final BooleanSetting HIDE_LYRICS_TRANSLATE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_translate_button", FALSE, true);
    public static final BooleanSetting HIDE_REPEAT_BUTTON = new BooleanSetting("morphe_music_hide_repeat_button", FALSE, true);
    public static final BooleanSetting HIDE_SHUFFLE_BUTTON = new BooleanSetting("morphe_music_hide_shuffle_button", FALSE, true);
    public static final BooleanSetting MINIPLAYER_NEXT_BUTTON = new BooleanSetting("morphe_music_miniplayer_next_button", TRUE, true);
    public static final BooleanSetting MINIPLAYER_PREVIOUS_BUTTON = new BooleanSetting("morphe_music_miniplayer_previous_button", TRUE, true);
    public static final BooleanSetting REMEMBER_REPEAT_STATE = new BooleanSetting("morphe_music_remember_repeat_state", FALSE, true, parentNot(HIDE_REPEAT_BUTTON));
    public static final BooleanSetting REMEMBER_SHUFFLE_STATE = new BooleanSetting("morphe_music_remember_shuffle_state", FALSE, true, parentNot(HIDE_SHUFFLE_BUTTON));
    public static final BooleanSetting SAVED_SHUFFLE_STATE = new BooleanSetting("morphe_music_saved_shuffle_state", FALSE, parent(REMEMBER_SHUFFLE_STATE));

    // Action buttons
    public static final BooleanSetting HIDE_ACTION_BAR = new BooleanSetting("morphe_music_hide_action_bar", FALSE, true);
    public static final BooleanSetting HIDE_LIKE_DISLIKE_BUTTON = new BooleanSetting("morphe_music_hide_like_dislike_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_COMMENTS_BUTTON = new BooleanSetting("morphe_music_hide_comments_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_LYRICS_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_share_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SAVE_BUTTON = new BooleanSetting("morphe_music_hide_save_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_DOWNLOAD_BUTTON = new BooleanSetting("morphe_music_hide_download_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_RADIO_BUTTON = new BooleanSetting("morphe_music_hide_radio_button", FALSE, true, parentNot(HIDE_ACTION_BAR));

    // Comments
    public static final BooleanSetting HIDE_COMMENTS_COMMUNITY_GUIDELINES = new BooleanSetting("morphe_music_hide_comments_community_guidelines", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_CONTEXT = new BooleanSetting("morphe_music_hide_comments_context", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_EMOJI_BUTTON = new BooleanSetting("morphe_music_hide_comments_emoji_button", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_INFO_BUTTON = new BooleanSetting("morphe_music_hide_comments_info_button", FALSE, true);
    public static final BooleanSetting HIDE_COMMENTS_TIMESTAMP_BUTTON = new BooleanSetting("morphe_music_hide_comments_timestamp_button", FALSE);

    // Flyout menu
    public static final BooleanSetting HIDE_FLYOUT_MENU_3_COLUMN_COMPONENT = new BooleanSetting("morphe_music_hide_flyout_menu_3_column_component", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_LIKE_DISLIKE = new BooleanSetting("morphe_music_hide_flyout_menu_like_dislike", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_TASTE_MATCH = new BooleanSetting("morphe_music_hide_flyout_menu_taste_match", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER = new BooleanSetting("morphe_music_hide_flyout_menu_add_to_listen_later", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_ADD_TO_QUEUE = new BooleanSetting("morphe_music_hide_flyout_menu_add_to_queue", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_CAPTIONS = new BooleanSetting("morphe_music_hide_flyout_menu_captions", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DELETE_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_delete_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DISMISS_QUEUE = new BooleanSetting("morphe_music_hide_flyout_menu_dismiss_queue", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DONT_RECOMMEND_ARTIST = new BooleanSetting("morphe_music_hide_flyout_menu_dont_recommend_artist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DOWNLOAD = new BooleanSetting("morphe_music_hide_flyout_menu_download", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_EDIT_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_edit_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_ALBUM = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_album", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_ARTIST = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_artist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_EPISODE = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_episode", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_PODCAST = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_podcast", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_HELP = new BooleanSetting("morphe_music_hide_flyout_menu_help", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_MARK_EPISODE_AS_PLAYED = new BooleanSetting("morphe_music_hide_flyout_menu_mark_episode_as_played", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_NOT_INTERESTED = new BooleanSetting("morphe_music_hide_flyout_menu_not_interested", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_PIN_TO_SPEED_DIAL = new BooleanSetting("morphe_music_hide_flyout_menu_pin_to_speed_dial", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_PLAY_NEXT = new BooleanSetting("morphe_music_hide_flyout_menu_play_next", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_QUALITY = new BooleanSetting("morphe_music_hide_flyout_menu_quality", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REMOVE_FROM_LIBRARY = new BooleanSetting("morphe_music_hide_flyout_menu_remove_from_library", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REMOVE_FROM_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_remove_from_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REPORT = new BooleanSetting("morphe_music_hide_flyout_menu_report", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SAVE_EPISODE_FOR_LATER_SAVE_TO_LIBRARY = new BooleanSetting("morphe_music_hide_flyout_menu_save_episode_for_later_save_to_library", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SAVE_TO_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_save_to_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SHARE = new BooleanSetting("morphe_music_hide_flyout_menu_share", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SHUFFLE_PLAY = new BooleanSetting("morphe_music_hide_flyout_menu_shuffle_play", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SLEEP_TIMER = new BooleanSetting("morphe_music_hide_flyout_menu_sleep_timer", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_START_RADIO = new BooleanSetting("morphe_music_hide_flyout_menu_start_radio", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_STATS_FOR_NERDS = new BooleanSetting("morphe_music_hide_flyout_menu_stats_for_nerds", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SUBSCRIBE = new BooleanSetting("morphe_music_hide_flyout_menu_subscribe", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_UNPIN_FROM_SPEED_DIAL = new BooleanSetting("morphe_music_hide_flyout_menu_unpin_from_speed_dial", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_VIEW_SONG_CREDIT = new BooleanSetting("morphe_music_hide_flyout_menu_view_song_credit", FALSE);

    // Crossfade
    public static final BooleanSetting CROSSFADE_ENABLED = new BooleanSetting("morphe_music_crossfade_enabled", FALSE, true);
    public static final EnumSetting<FadeCurve> CROSSFADE_CURVE = new EnumSetting<>("morphe_music_crossfade_curve", FadeCurve.EQUAL_POWER, parent(CROSSFADE_ENABLED));
    public static final EnumSetting<CrossFadeDuration> CROSSFADE_DURATION = new EnumSetting<>("morphe_music_crossfade_duration", CrossFadeDuration.MILLISECONDS_3000, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_SKIP = new BooleanSetting("morphe_music_crossfade_on_skip", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_AUTO_ADVANCE = new BooleanSetting("morphe_music_crossfade_on_auto_advance", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_SESSION_CONTROL = new BooleanSetting("morphe_music_crossfade_session_control", TRUE, parent(CROSSFADE_ENABLED));

    // Miscellaneous
    public static final EnumSetting<ClientType> SPOOF_VIDEO_STREAMS_CLIENT_TYPE = new EnumSetting<>("morphe_spoof_video_streams_client_type", ClientType.VISIONOS_1_02, true, parent(SPOOF_VIDEO_STREAMS));

    // Scrobbling
    public static final BooleanSetting LISTENBRAINZ_SCROBBLING = new BooleanSetting("morphe_music_listenbrainz_enabled", FALSE, true);
    public static final StringSetting LISTENBRAINZ_USER_TOKEN = new StringSetting("morphe_music_listenbrainz_token", "", false, parent(LISTENBRAINZ_SCROBBLING));
    public static final BooleanSetting LISTENBRAINZ_NOW_PLAYING = new BooleanSetting("morphe_music_listenbrainz_now_playing", FALSE, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_MIN_SONG_DURATION = new IntegerSetting("morphe_music_listenbrainz_min_song_duration", 30, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_DELAY_PERCENT = new IntegerSetting("morphe_music_listenbrainz_delay_percent", 50, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_DELAY_SECONDS = new IntegerSetting("morphe_music_listenbrainz_delay_seconds", 180, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final BooleanSetting LASTFM_SCROBBLING = new BooleanSetting("morphe_music_lastfm_enabled", FALSE, true);
    public static final StringSetting LASTFM_SESSION_KEY = new StringSetting("morphe_music_lastfm_session_key", "", false, parent(LASTFM_SCROBBLING));
    public static final StringSetting LASTFM_USERNAME = new StringSetting("morphe_music_lastfm_username", "", false, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting LASTFM_NOW_PLAYING = new BooleanSetting("morphe_music_lastfm_now_playing", FALSE, true, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting LASTFM_LOVE_ON_LIKE = new BooleanSetting("morphe_music_lastfm_love_on_like", FALSE, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_MIN_SONG_DURATION = new IntegerSetting("morphe_music_lastfm_min_song_duration", 30, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_DELAY_PERCENT = new IntegerSetting("morphe_music_lastfm_delay_percent", 50, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_DELAY_SECONDS = new IntegerSetting("morphe_music_lastfm_delay_seconds", 180, true, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting SCROBBLING_METADATA_CLEANUP = new BooleanSetting("morphe_music_scrobbling_metadata_cleanup", TRUE, true, parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING));
    public static final StringSetting SCROBBLING_CUSTOM_REGEX = new StringSetting("morphe_music_scrobbling_custom_regex", "", true, parentsAll(parent(SCROBBLING_METADATA_CLEANUP), parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING)));
    public static final BooleanSetting SCROBBLING_PARSE_TITLE = new BooleanSetting("morphe_music_scrobbling_parse_title", FALSE, true, parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING));

    // Lyrics
    public static final BooleanSetting LYRICS_ENABLED = new BooleanSetting("morphe_music_lyrics_enabled", TRUE, true);
    public static final String DEFAULT_LYRICS_ORDER =
            "LRCLIB,QQ,NetEase,KuGou,-bLyrics,-BiniLyrics,-Unison,-AMLL,Apple,Musixmatch,Spotify";
    public static final StringSetting LYRICS_SOURCE = new StringSetting("morphe_music_lyrics_source", DEFAULT_LYRICS_ORDER, true, parent(LYRICS_ENABLED));
    public static final StringSetting APPLE_MUSIC_TOKEN = new StringSetting("morphe_music_apple_music_token", "", true, parent(LYRICS_ENABLED));
    public static final StringSetting MUSIXMATCH_TOKEN = new StringSetting("morphe_music_musixmatch_token", "", true, parent(LYRICS_ENABLED));
    public static final StringSetting SPOTIFY_TOKEN = new StringSetting("morphe_music_spotify_token", "", true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_TRANSLATE = new BooleanSetting("morphe_music_lyrics_translate", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_TAP_TO_SEEK = new BooleanSetting("morphe_music_lyrics_tap_to_seek", TRUE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_COPY_BUTTON = new BooleanSetting("morphe_music_lyrics_show_copy_button", TRUE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_TRANSLATE_BUTTON = new BooleanSetting("morphe_music_lyrics_show_translate_button", TRUE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_ROMANIZE_BUTTON = new BooleanSetting("morphe_music_lyrics_show_romanize_button", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_REFRESH_BUTTON = new BooleanSetting("morphe_music_lyrics_show_refresh_button", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_ROMANIZE = new BooleanSetting("morphe_music_lyrics_romanize", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_WORD_SYNC = new BooleanSetting("morphe_music_lyrics_word_sync", TRUE, true, parent(LYRICS_ENABLED));
    public static final IntegerSetting LYRICS_TEXT_SIZE = new IntegerSetting("morphe_music_lyrics_text_size", 24, true, parent(LYRICS_ENABLED));
    public static final IntegerSetting LYRICS_OFFSET_MS = new IntegerSetting("morphe_music_lyrics_offset_ms", 0, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_MEDIASESSION = new BooleanSetting("morphe_music_lyrics_mediasession", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_MINIPLAYER = new BooleanSetting("morphe_music_lyrics_miniplayer", FALSE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_DISPLAY_ARTIST_FIRST = new BooleanSetting("morphe_music_lyrics_display_artist_first", TRUE, true, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_USE_EMBEDDED = new BooleanSetting("morphe_music_lyrics_use_embedded", TRUE, true, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_CAPTION_COOKIES = new StringSetting("morphe_music_lyrics_caption_cookies", "", true, parent(LYRICS_ENABLED));
    public static final String DEFAULT_LYRICS_REGEX =
            "(?i)\\s*[（(\\[]((official\\s+)?(video|audio|music\\s+video|lyrics?\\s+video|visualizer|mv))[）)\\]]"
            + "|(?i)\\s*[（(\\[]((\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?)[）)\\]]"
            + "|(?i)\\s*[（(\\[](mono|stereo|hq|hd|4k|8k)[）)\\]]"
            + "|[（(][^）)]*(?:主题曲|片尾曲|插曲|片头曲|广告曲|推广曲)[^）)]*[）)]"
            + "|[（(][^）)]*[\\uff1a:][^）)]*[）)]"
            + "|(?i)\\s*-\\s*topic$";
    public static final StringSetting LYRICS_CUSTOM_REGEX = new StringSetting("morphe_music_lyrics_custom_regex", DEFAULT_LYRICS_REGEX, true, parent(LYRICS_ENABLED));
    public static final String DEFAULT_LYRICS_TEXT_FILTER =
            ".*?(?:"
            + "未经.*?(?:不得|禁止)"
            + "|本作品声明.*?著作权权利保留.*?不得"
            + "|本字幕由TME AI技术生成"
            + "|部分素材源自网络"
            + "|酷我音乐.*?特别出品"
            + "|酷狗.*?星曜计划"
            + "|酷狗.*?国潮"
            + "|酷狗音乐.*?就是歌多"
            + "|听国潮.*?酷狗"
            + "|未经许可.*?(?:翻唱|盗版)"
            + "|本作品.*?授权"
            + "|已获得.*?授权"
            + "|星曜计划.*?企划|黑胶复刻"
            + "|此歌曲为没有填词的纯音乐"
            + "|纯音乐，请欣赏"
            + "|此歌曲由Vemus未音APP\\.制作 音乐创作如此简单！"
            + "|酷狗音乐『万物皆可dj』企划"
            + "|『听dj, 到中国酷狗』"
            + "|本歌曲来自〖飓风计划〗"
            + "|10亿现金激励，千亿流量扶持！"
            + ").*";
    public static final StringSetting LYRICS_TEXT_FILTER = new StringSetting("morphe_music_lyrics_text_filter", DEFAULT_LYRICS_TEXT_FILTER, true, parent(LYRICS_ENABLED));
    public static final String DEFAULT_LYRICS_CREDIT_LINE_REGEX =
            "词,曲,编曲,制作,统筹,监制,发行,原唱,混音,母带,录音,人声,合声,和声,配唱,吉他,贝斯,出品,"
            + "企划,封面,版权,营销,推广,策划,舞台,灯光,合音,和音,团队,助理,"
            + "大提琴,中提琴,小提琴,二胡,笛子,口琴,班卓琴,钢琴,键盘,鼓,打击乐,弦乐,"
            + "演唱,歌名,歌手,创作者,艺术家,采样,原版,音频,音响,音乐,乐队,演奏,"
            + "微信,微博,视觉,联合,联系,私人,"
            + "电吉他,电钢琴,木吉他,低音提琴,"
            + "Lyric,Music,Arranger,Composer,Produced,Recording,Voice,Backing,Executive,Manufacturing,Rap,OP,SP,"
            + "A&R,A.Guita,Arranged by,Assistant Engineer,Background Vocals,Bass,Composed by,Credit,Drums,"
            + "E.Guitar,Engineered by,Percussion,Performing,Program,QQ,Recorded at,Strings,Synthesizer,"
            + "Pro-Tools Editing,Surround,"
            + "Vocal Directed by,Recorded by,Digital Edited by,Mixed by,Mastered by,Original Title,"
            + "Sub Publisher,Publisher,Main Sample,Sample,"
            + "Vocals Arrangement,Digital Editing,Mix Engineer,All Instruments,Keyboard,"
            + "Vocal Arrangement,Background Vocal,"
            + "Assistant Mix Engineers,Assistant Mix Engineer,"
            + "Mastering Engineers,Mastering Engineer,"
            + "Engineer,Master,Mastering,Engineering,"
            + "Artists Agency,Agency,Artists,Artist,"
            + "Publishing Group administered by,Administered,Administer,Administering,"
            + "Lead Vocal,Harmony,Guitar,Drum,Leader,Lead,"
            + "Child Lead,Child Choir Instruction,Vocal Producer,"
            + "作词,作曲,马头琴,ISRC,海外配唱执行,执行,原歌名,原词曲,改编词,"
            + "中提,大提,小提,企业宣传,企宣统筹,宣传统筹,宣发,前置混音,"
            + "声乐指导老师,声乐指导,指导,歌曲联合发行,监唱Vocal Producer,联合发行,歌曲发行,监唱,"
            + "作者,画师,作画,DJ,Cover,封设,原著,海报,"
            + "翻译,译者,Author（作词）,总监制,修音师 Editing Engineer,项目企划,项目营销,总策划,"
            + "dj制作人dj producer,项目统筹project overall planning,项目统筹,执行制作 production assistant,"
            + "童声,原词,改编,Vocals,艺人统筹,艺人制作统筹,混缩室 mixing studio,混缩室,Mixing,Mixer,Written by,Copyright,"
            + "箫,题字,后期,美工,美术设计,宣传,发布者,发布,笛,笛萧,"
            + "PV,剧情策划,编剧,剧情后期,剧情,书法,导演,翻唱,古筝,"
            + "CV,COS,COS图,工作室,原画,萧笛,作词Lyrics by,作曲Composition by,小号Trumpet,小号,"
            + "总企划,项目总企划,商务统筹,商务,商务合作,合作者,合作,创作,"
            + "填词,谱曲,抖音,快手,原曲,原词,原翻,混缩,作词Lyrics,作词lyrics,作曲music,"
            + "说唱,说唱词,艺人经纪公司,经纪公司,经纪,联系方式,"
            + "音编,曲编,编著,后期制作,琵琶,制作人,原曲制作人,唢呐,助力推广,翻唱混音,漫画,设计,"
            + "文案,平面设计,编,合成,合成器,宣推,"
            + "花脸,板胡,长号,次中音萨克斯,长笛,萨克斯,口风琴,手风琴,鼓机,"
            + "舞蹈总监,编舞师,舞团,编舞,舞蹈,领舞,伴舞,"
            + "官方指定音乐合作伙伴,指定音乐合作伙伴,官方音乐合作伙伴,合作伙伴,"
            + "PGM,Autotune,乐团,总顾问,项目协力,项目总监,协力,"
            + "主唱,伴唱,合唱,男声,女声,"
            + "附加制作,附加,厂牌,"
            + "乐器,乐器录音,伴奏,"
            + "中文作词,中文填词,中文,英文,英文填词,英文作词,粤语,粤语填词,粤语作词,"
            + "舞曲制作,舞曲,"
            + "歌曲企划,歌曲混音,歌曲,"
            + "特别鸣谢,鸣谢,"
            + "艺术指导,录音指导,混音指导,美术指导,声音监制,声音,"
            + "编导,造型,造型顾问,顾问,"
            + "人声监制,人声录音师,编辑,人声编辑,人声制作,人声录音棚,"
            + "修音,贴唱,贴唱混音,调音,调校,校准,音准调校,"
            + "念白,念白混音,题记,记,"
            + "吟唱,编写,吟唱编写,歌声,"
            + "歌词改编,艺统,主催,"
            + "杜比全景声混音,杜比全景声,"
            + "艺人经纪artists agency,艺人经纪,"
            + "原编曲,剪纸艺术家,"
            + "业务联系,业务邮箱,邮箱地址,邮件地址,邮件,"
            + "音效,音效设计,音效指导,"
            + "总监,领唱,领唱指导,合唱指导,演唱指导,"
            + "音乐人,飓风计划商务合作,网易音乐人商务合作,"
            + "滤镜,MV滤镜,图片,图片编辑,图片摄影,摄影,拍摄,图片拍摄,"
            + "MV,MV制作,MV拍摄,MV摄影,MV后期,"
            + "场景提供,场景,"
            + "童声领唱,童声合唱指导";
    public static final StringSetting LYRICS_CREDIT_LINE_REGEX = new StringSetting("morphe_music_lyrics_credit_line_regex", DEFAULT_LYRICS_CREDIT_LINE_REGEX, true, parent(LYRICS_ENABLED));

    // SponsorBlock
    public static final BooleanSetting SB_ENABLED = new BooleanSetting("morphe_sb_enabled", TRUE);
    public static final BooleanSetting SB_TOAST_ON_SKIP = new BooleanSetting("morphe_sb_toast_on_skip", TRUE, parent(SB_ENABLED));
    public static final BooleanSetting SB_TOAST_ON_CONNECTION_ERROR = new BooleanSetting("morphe_sb_toast_on_connection_error", TRUE, parent(SB_ENABLED));
    public static final StringSetting SB_API_URL = new StringSetting("morphe_sb_api_url", "https://sponsor.ajay.app", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR = new StringSetting("morphe_sb_sponsor", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR_COLOR = new StringSetting("morphe_sb_sponsor_color", "#FF00D400", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO = new StringSetting("morphe_sb_selfpromo", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO_COLOR = new StringSetting("morphe_sb_selfpromo_color", "#FFFFFF00", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION = new StringSetting("morphe_sb_interaction", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION_COLOR = new StringSetting("morphe_sb_interaction_color", "#FFCC00FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO = new StringSetting("morphe_sb_intro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO_COLOR = new StringSetting("morphe_sb_intro_color", "#FF00FFFF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO = new StringSetting("morphe_sb_outro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO_COLOR = new StringSetting("morphe_sb_outro_color", "#FF0202ED", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW = new StringSetting("morphe_sb_preview", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW_COLOR = new StringSetting("morphe_sb_preview_color", "#FF008FD6", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK = new StringSetting("morphe_sb_hook", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK_COLOR = new StringSetting("morphe_sb_hook_color", "#FF395699", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER = new StringSetting("morphe_sb_filler", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER_COLOR = new StringSetting("morphe_sb_filler_color", "#FF7300FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC = new StringSetting("morphe_sb_music_offtopic", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC_COLOR = new StringSetting("morphe_sb_music_offtopic_color", "#FFFF9900", parent(SB_ENABLED));

    // Migration
    private static final BooleanSetting DEPRECATED_HIDE_CATEGORY_BAR = new BooleanSetting("morphe_music_hide_category_bar", FALSE, true);
    private static final BooleanSetting DEPRECATED_PERMANENT_REPEAT = new BooleanSetting("morphe_music_permanent_repeat", FALSE, true);

    static {
        migrateOldSettingToNew(DEPRECATED_HIDE_CATEGORY_BAR, HIDE_FILTER_BAR);
        migrateOldSettingToNew(DEPRECATED_PERMANENT_REPEAT, REMEMBER_REPEAT_STATE);
    }

    static {
        if (!SPOOF_APP_VERSION_TARGET.isSetToDefault() &&
                SPOOF_APP_VERSION_TARGET.get().compareTo(SPOOF_APP_VERSION_TARGET.defaultValue) < 0) {
            Logger.printInfo(() -> "Resetting spoof app version");
            SPOOF_APP_VERSION_TARGET.resetToDefault();
        }

        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_MIN_SONG_DURATION,
                10, 60, 5, "s"));
        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_DELAY_PERCENT,
                30, 95, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_DELAY_SECONDS,
                30, 360, 10, "s"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_MIN_SONG_DURATION,
                10, 60, 5, "s"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_DELAY_PERCENT,
                30, 95, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_DELAY_SECONDS,
                30, 360, 10, "s"));
        SeekBarPreference.register(new SeekBarConfig(LYRICS_TEXT_SIZE,
                14, 40, 2, "sp"));
        SeekBarPreference.register(new SeekBarConfig(LYRICS_OFFSET_MS,
                -2000, 2000, 100, "ms"));

        // Must run before any code reads a SegmentCategory setting.
        MusicSponsorBlockConfig.install();
    }
}
