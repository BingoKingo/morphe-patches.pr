/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.romanizers.ProperNounStore;

/** Combines the user's proper-noun list with the downloaded multilingual filter. */
public final class ProperNounFilter {

    private final Set<String> customTokens;
    private final Set<String> customPhrases;

    public ProperNounFilter(@Nullable Collection<String> customTokens) {
        this(customTokens, null);
    }

    public ProperNounFilter(@Nullable Collection<String> customTokens,
                            @Nullable Collection<String> customPhrases) {
        this.customTokens = fold(customTokens);
        this.customPhrases = fold(customPhrases);
    }

    @NonNull
    private static Set<String> fold(@Nullable Collection<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                String v = value.toLowerCase(Locale.ROOT).trim();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    @NonNull
    public static ProperNounFilter from(@Nullable String language,
                                        @Nullable Collection<String> customTokens) {
        return new ProperNounFilter(customTokens);
    }

    public boolean containsToken(@NonNull String lowercaseToken) {
        return customTokens.contains(lowercaseToken) || ProperNounStore.mightContain(lowercaseToken);
    }

    /** Short n-grams are checked against the same filter as single tokens. */
    public boolean mightBePhrase(@NonNull String lowercasePhrase) {
        return customPhrases.contains(lowercasePhrase)
                || ProperNounStore.mightContain(lowercasePhrase);
    }
}
