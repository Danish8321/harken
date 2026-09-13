package com.harken.android.data

/**
 * Where a recording is between being captured and having a transcript.
 *
 * This is the whole vocabulary. It used to be a nullable `String` compared against literals
 * in three UI files, and the cost of that was exactly what a missing type costs: `"Pending"`
 * was read in four places — including the guard that decides whether a row may be deleted —
 * and written nowhere in the app's entire history (ARC-064). Nothing could say so, because
 * nothing named the set.
 *
 * [stored] is what goes in `sessions.transcriptionStatus`, and it is the only definition of
 * that: the DAO binds these values rather than writing literals of its own, so a member and
 * the database cannot drift. This repo has no Robolectric and no in-memory Room on the JVM,
 * so DAO behaviour cannot be tested here — construction is the guard that is available.
 */
enum class TranscriptionStatus(
    val stored: String,
) {
    /**
     * Captured, and waiting on the user. Written once, when the row is created
     * (`SessionRepository.createLocalSession`) — an import arrives here too (ADR-0016).
     * The card offers Transcribe. Transcription never starts by itself (ADR-0007).
     */
    Recorded("Recorded"),

    /**
     * Being decoded right now, by the one on-device transcription the app runs at a time.
     * Written by `SessionDao.markLocalTranscriptionStarted`, and read back as the answer to
     * "is this row transcribing?" — but not the only source of that answer while the write
     * is still in flight; see `LibraryUiState.statusOf`.
     *
     * Nothing in this state survives the process, so every `Running` row found at launch is
     * a leftover and is settled as [Failed] (`SessionDao.failInterruptedTranscriptions`).
     */
    Running("Running"),

    /** Decoded, with its segments written in the same transaction. The card shows Transcribed. */
    Succeeded("Succeeded"),

    /**
     * The decode raised, or the process died mid-decode. Carries a `transcriptionFailureReason`.
     * The card offers Transcribe again: a failed transcription and one never started leave
     * the user with the same thing to do.
     */
    Failed("Failed"),
    ;

    companion object {
        /**
         * Reads what the database holds.
         *
         * Anything unrecognised — including null, which the column still permits — is
         * [Recorded]. That is the recoverable direction: it offers Transcribe, so the worst
         * case is a user transcribing something already transcribed. The fallback used to be
         * "Transcribed", which asserted finished work about a row nothing could vouch for.
         */
        fun of(stored: String?): TranscriptionStatus = entries.firstOrNull { it.stored == stored } ?: Recorded
    }
}
