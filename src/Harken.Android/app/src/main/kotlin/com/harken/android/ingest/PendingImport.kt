package com.harken.android.ingest

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A file handed to Harken from outside, waiting for the app to be on screen.
 *
 * The share sheet delivers to an Activity, and the Activity has no coroutine scope worth
 * staging a gigabyte on, no way to ask the user whether a large import is wanted, and no
 * surface to say "finish the recording first" on. Everything that answers those already
 * exists in `ImportViewModel`, one composition away. So the Activity does the one thing only
 * it can — read the Uri while its grant is still good — and leaves it here.
 *
 * Taken exactly once: a shared file that survived a rotation and imported itself twice would
 * be two Sessions of the same audio, and the second one silently costs the storage of the
 * first.
 */
object PendingImport {
    private val _uri = MutableStateFlow<Uri?>(null)

    /** Non-null while something is waiting to be picked up. */
    val uri: StateFlow<Uri?> = _uri.asStateFlow()

    fun offer(uri: Uri) {
        _uri.value = uri
    }

    /** The waiting file, if any, and clears it in the same step. */
    fun take(): Uri? = _uri.getAndUpdate { null }
}
