/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;

/**
 * Runtime gates for derived lyrics work (AI sections, translation, romanization, ruby).
 *
 * <p>A saved setting keeps its value after its parent is turned off: the settings screen grays it
 * out using {@code isAvailable()}, but {@code get()} still returns the old value. Every place that
 * starts derived work, or applies its result, must go through these gates instead of reading a
 * saved value on its own.
 */
public final class LyricsAvailability {

    private LyricsAvailability() {
    }

    /** AI sections need lyrics, section tags, and the AI switch itself. */
    public static boolean aiSections() {
        return Settings.LYRICS_USE_AI_SECTION.get()
                && Settings.LYRICS_USE_AI_SECTION.isAvailable();
    }

    /** Translation needs lyrics and a visible translate button. */
    public static boolean translation() {
        return Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get()
                && Settings.LYRICS_SHOW_TRANSLATE_BUTTON.isAvailable();
    }

    /** Romanization needs lyrics and a visible romanize button. */
    public static boolean romanization() {
        return Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get()
                && Settings.LYRICS_SHOW_ROMANIZE_BUTTON.isAvailable();
    }

    /** Ruby is a sub-setting of the romanize button, so it shares that gate. */
    public static boolean ruby() {
        return Settings.LYRICS_RUBY_LANGUAGE.isAvailable();
    }

    /** Whether the saved target language may be used; false while its button is hidden. */
    public static boolean useSavedTranslationLanguage() {
        return Settings.LYRICS_TRANSLATION_LANGUAGE.isAvailable();
    }

    /** The saved language is ignored while its button is hidden. */
    public static String translationLanguage() {
        return useSavedTranslationLanguage()
                ? LyricsRequests.translationLanguage()
                : LyricsRequests.deviceLanguage();
    }
}
