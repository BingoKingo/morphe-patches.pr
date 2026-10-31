/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsManager.ProviderFetch;
import app.morphe.extension.music.patches.lyrics.LyricsManager.ScoredCandidate;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;

final class DisplayState {

    final boolean wordSync;
    /** An explicit search waits for every stage, so the best result of the run is the one shown. */
    final boolean exhaustive;
    @Nullable
    Lyrics published;
    long firstPaintAt;
    @Nullable
    Lyrics bestResult;
    int bestDisplayRank;
    @Nullable
    Lyrics bestFallback;
    int bestFallbackRank;
    boolean windowClosed;

    DisplayState(boolean wordSync, boolean exhaustive) {
        this.wordSync = wordSync;
        this.exhaustive = exhaustive;
        this.bestDisplayRank = wordSync ? -1 : -2;
        // Plain (rank 0) high-match must qualify as endgame fallback; default 0 would not.
        this.bestFallbackRank = -1;
    }

    boolean accept(@Nullable ProviderFetch pf, List<ScoredCandidate> scoreCandidates,
                   boolean stage12) {
        if (!isCandidate(pf)) {
            return false;
        }
        final Lyrics fetched = pf.lyrics();
        final int match = pf.matchScore();
        final int rank = LyricsRequests.syncRank(fetched);
        final int composite = LyricsRequests.composite(match, fetched, pf.penalty());
        scoreCandidates.add(new ScoredCandidate(composite, rank, fetched));

        if (LyricsRequests.isCustomMatchMode()) {
            return acceptCustom(pf, fetched, composite);
        }

        if (!pf.highMatch()) {
            return false;
        }
        if (rank > bestFallbackRank) {
            bestFallback = fetched;
            bestFallbackRank = rank;
        }
        if (wordSync) {
            return acceptWordSync(rank, fetched);
        }
        return acceptDisplay(rank, fetched, stage12);
    }

    /** A fetch worth scoring at all: it matched by itself, or the search was blind. */
    private static boolean isCandidate(@Nullable ProviderFetch pf) {
        return pf != null
                && (pf.highMatch() || pf.blind())
                && LyricsMatch.isValidLyrics(pf.lyrics(), null);
    }

    /** Both slots take the highest composite; the run always continues. */
    private boolean acceptCustom(ProviderFetch pf, Lyrics fetched, int composite) {
        if (composite > bestFallbackRank) {
            bestFallback = fetched;
            bestFallbackRank = composite;
        }
        if (composite > bestDisplayRank) {
            bestResult = fetched;
            bestDisplayRank = composite;
        }
        return false;
    }

    /** While word timings are on, only a fully word-synced line set may replace the screen. */
    private boolean acceptWordSync(int rank, Lyrics fetched) {
        if (rank != 2) {
            return false;
        }
        bestResult = fetched;
        return !exhaustive;
    }

    /**
     * Demoting word timing below the weaker kinds let a later result replace it on screen.
     */
    private boolean acceptDisplay(int rank, Lyrics fetched, boolean stage12) {
        if (rank > bestDisplayRank) {
            bestResult = fetched;
            bestDisplayRank = rank;
        }
        return !exhaustive && stage12 && rank == 1 && bestResult != null;
    }
}
