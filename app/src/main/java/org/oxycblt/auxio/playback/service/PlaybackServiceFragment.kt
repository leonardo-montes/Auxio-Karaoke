/*
 * Copyright (c) 2024 Auxio Project
 * PlaybackServiceFragment.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import javax.inject.Inject
import kotlinx.coroutines.Job
import org.oxycblt.auxio.AuxioService.Companion.INTENT_KEY_START_ID
import org.oxycblt.auxio.ForegroundListener
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.widgets.WidgetComponent
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

class PlaybackServiceFragment
private constructor(
    private val context: Context,
    private val foregroundListener: ForegroundListener,
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
    exoHolderFactory: ExoPlaybackStateHolder.Factory,
    sessionHolderFactory: MediaSessionHolder.Factory,
    widgetComponentFactory: WidgetComponent.Factory,
    systemReceiverFactory: SystemPlaybackReceiver.Factory,
) : PlaybackStateManager.Listener {
    class Factory
    @Inject
    constructor(
        private val playbackManager: PlaybackStateManager,
        private val playbackSettings: PlaybackSettings,
        private val exoHolderFactory: ExoPlaybackStateHolder.Factory,
        private val sessionHolderFactory: MediaSessionHolder.Factory,
        private val widgetComponentFactory: WidgetComponent.Factory,
        private val systemReceiverFactory: SystemPlaybackReceiver.Factory,
    ) {
        fun create(context: Context, foregroundListener: ForegroundListener) =
            PlaybackServiceFragment(
                context,
                foregroundListener,
                playbackManager,
                playbackSettings,
                exoHolderFactory,
                sessionHolderFactory,
                widgetComponentFactory,
                systemReceiverFactory,
            )
    }

    private val waitJob = Job()
    private val exoHolder = exoHolderFactory.create()
    private val sessionHolder = sessionHolderFactory.create(context, foregroundListener)
    private val widgetComponent = widgetComponentFactory.create(context)
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val systemReceiver =
        systemReceiverFactory.create(
            context,
            widgetComponent,
            onExitRequested = { handleExitRequest() },
        )

    // Tracks whether an intentional exit has been requested.
    private var exitRequested = false

    private fun isAppForegrounded(): Boolean {
        val processes = activityManager.runningAppProcesses ?: return false
        val pkg = context.packageName
        val own = processes.find { it.processName == pkg } ?: return false
        val imp = own.importance
        val visible =
            // IMPORTANCE_FOREGROUND (100): Process in foreground UI, user is interacting.
            // IMPORTANCE_VISIBLE (200): Process visible, but not in immediate foreground.
            imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        L.d("Importance: $imp, visible: $visible")
        return visible
    }

    private fun handleExitRequest() {
        if (isAppForegrounded()) {
            // (x) in notification will no longer exist, but it might still
            // be triggered by other ways, e.g. Bluetooth devices.
            L.d("Exit Requested: App visible, pausing")
            playbackManager.playing(false)
        } else {
            L.d("Exit Requested: App not visible, exiting")
            exitRequested = true
            playbackManager.endSession()
        }
    }

    // --- MEDIASESSION CALLBACKS ---

    fun attach(): MediaSessionCompat.Token {
        exoHolder.attach()
        sessionHolder.attach()
        widgetComponent.attach()
        systemReceiver.attach()
        playbackManager.addListener(this)
        return sessionHolder.token
    }

    fun handleTaskRemoved() {
        val shouldExit =
            !playbackManager.progression.isPlaying || playbackSettings.exitOnTaskRemoval

        if (shouldExit) {
            exitRequested = true
            playbackManager.endSession()
        }
    }

    fun start(intent: Intent?) {
        // At minimum we want to ensure an active playback state.
        // TODO: Possibly also force to go foreground?
        val startId = intent?.getIntExtra(INTENT_KEY_START_ID, -1)
        val action =
            when (startId) {
                IntegerTable.START_ID_ACTIVITY -> null
                IntegerTable.START_ID_TASKER ->
                    DeferredPlayback.RestoreState(
                        play = true,
                        fallback = DeferredPlayback.ShuffleAll,
                    )
                IntegerTable.START_ID_MEDIA_BUTTON -> {
                    if (!sessionHolder.tryMediaButtonIntent(intent)) {
                        // Malformed intent, need to restore state immediately
                        DeferredPlayback.RestoreState(
                            play = true,
                            fallback = DeferredPlayback.ShuffleAll,
                        )
                    } else {
                        null
                    }
                }
                else -> {
                    L.d("Handling non-native start.")
                    if (intent != null && sessionHolder.tryMediaButtonIntent(intent)) {
                        // Just a media button intent, move on.
                        return
                    }
                    // External services using Auxio better know what they are doing.
                    DeferredPlayback.RestoreState(play = false)
                }
            }
        if (action != null) {
            L.d("Initing service fragment using action $action")
            playbackManager.playDeferred(action)
        }
    }

    val notification: ForegroundServiceNotification?
        get() = if (exoHolder.sessionOngoing) sessionHolder.notification else null

    val shouldStopService: Boolean
        get() = exitRequested && !exoHolder.sessionOngoing

    fun release() {
        waitJob.cancel()
        playbackManager.removeListener(this)
        systemReceiver.release()
        widgetComponent.release()
        sessionHolder.release()
        exoHolder.release()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        exitRequested = false
    }

    override fun onSessionEnded() {
        foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
    }
}
