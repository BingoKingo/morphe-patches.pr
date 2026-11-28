/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.settings.Settings;

public class LyricsAvailabilityTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void aiSectionsOnlyWhileTagsAndLyricsAreOn() {
        final boolean prevEnabled = Settings.LYRICS_ENABLED.get();
        final boolean prevUseAi = Settings.LYRICS_USE_AI_SECTION.get();
        final boolean prevShow = Settings.LYRICS_SHOW_SECTION.get();
        try {
            Settings.LYRICS_ENABLED.save(true);
            Settings.LYRICS_SHOW_SECTION.save(true);
            Settings.LYRICS_USE_AI_SECTION.save(true);
            assertTrue(LyricsAvailability.aiSections());

            Settings.LYRICS_SHOW_SECTION.save(false);
            assertFalse(LyricsAvailability.aiSections());
            assertTrue(Settings.LYRICS_USE_AI_SECTION.get());

            Settings.LYRICS_SHOW_SECTION.save(true);
            Settings.LYRICS_ENABLED.save(false);
            assertFalse(LyricsAvailability.aiSections());
        } finally {
            Settings.LYRICS_ENABLED.save(prevEnabled);
            Settings.LYRICS_USE_AI_SECTION.save(prevUseAi);
            Settings.LYRICS_SHOW_SECTION.save(prevShow);
        }
    }

    @Test
    public void translationOnlyWhileButtonAndLyricsAreOn() {
        final boolean prevEnabled = Settings.LYRICS_ENABLED.get();
        final boolean prevButton = Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get();
        try {
            Settings.LYRICS_ENABLED.save(true);
            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(true);
            assertTrue(LyricsAvailability.translation());

            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(false);
            assertFalse(LyricsAvailability.translation());

            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(true);
            Settings.LYRICS_ENABLED.save(false);
            assertFalse(LyricsAvailability.translation());
        } finally {
            Settings.LYRICS_ENABLED.save(prevEnabled);
            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(prevButton);
        }
    }

    @Test
    public void romanizationAndRubyFollowTheRomanizeButton() {
        final boolean prevEnabled = Settings.LYRICS_ENABLED.get();
        final boolean prevButton = Settings.LYRICS_SHOW_ROMANIZE_BUTTON.get();
        try {
            Settings.LYRICS_ENABLED.save(true);
            Settings.LYRICS_SHOW_ROMANIZE_BUTTON.save(true);
            assertTrue(LyricsAvailability.romanization());
            assertTrue(LyricsAvailability.ruby());

            Settings.LYRICS_SHOW_ROMANIZE_BUTTON.save(false);
            assertFalse(LyricsAvailability.romanization());
            assertFalse(LyricsAvailability.ruby());
        } finally {
            Settings.LYRICS_ENABLED.save(prevEnabled);
            Settings.LYRICS_SHOW_ROMANIZE_BUTTON.save(prevButton);
        }
    }

    @Test
    public void savedTranslationLanguageIgnoredWhileButtonIsHidden() {
        final boolean prevButton = Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get();
        try {
            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(true);
            assertTrue(LyricsAvailability.useSavedTranslationLanguage());

            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(false);
            assertFalse(LyricsAvailability.useSavedTranslationLanguage());
        } finally {
            Settings.LYRICS_SHOW_TRANSLATE_BUTTON.save(prevButton);
        }
    }
}
