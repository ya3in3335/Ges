package dz.association.stats.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live updates between the two apps. While an app is on screen it asks the server for a small change
 * counter every few seconds and tells the open screens to reload as soon as it changes (a registration
 * in the members' app appears in the admin app, a confirmed attendance appears on the member's card).
 * When the network fails it slows down and keeps trying. Screens also reload on pull and on reopening.
 */
@Singleton
class SyncPoller @Inject constructor(private val api: StatsApi) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()
    private var last: String? = null

    /** Runs until cancelled (the activity collects it only while started). */
    suspend fun run(intervalMs: Long = 4_000, maxBackoffMs: Long = 30_000) {
        var wait = intervalMs
        while (true) {
            wait = try {
                val v = api.sync().version
                if (last != null && v != last) _changes.tryEmit(Unit)
                last = v
                intervalMs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                (wait * 2).coerceAtMost(maxBackoffMs)
            }
            delay(wait)
        }
    }

    /** Forces the open screens to reload now (after the app itself changed something). */
    fun poke() { _changes.tryEmit(Unit) }
}
