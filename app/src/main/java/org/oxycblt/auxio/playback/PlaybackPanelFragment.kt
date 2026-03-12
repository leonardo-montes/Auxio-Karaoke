/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.widget.Toolbar
import androidx.camera.core.CameraSelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.ControlledCoverView
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    ControlledCoverView.OnSwipeListener,
    ViewTreeObserver.OnGlobalLayoutListener {
    @Inject lateinit var musicSettings: MusicSettings

    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private var lastCoverWidth = 0

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }
        
        binding.playbackCameraToggle?.setOnClickListener { playbackModel.toggleCamera() }

        binding.playbackCover.onSwipeListener = this
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.setOnClickListener { playbackModel.togglePlaying() }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        // Karaoke controls
        binding.karaokeVocalsToggle?.setOnClickListener { playbackModel.toggleVocals() }
        binding.karaokeAccompanimentToggle?.setOnClickListener { playbackModel.toggleAccompaniment() }
        
        binding.karaokeVocalsVolume?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                playbackModel.setVocalsVolume(slider.value.toInt())
            }
        })
        
        binding.karaokeAccompanimentVolume?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                playbackModel.setAccompanimentVolume(slider.value.toInt())
            }
        })

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.showLyrics, ::updateLyricsVisibility)
        collectImmediately(playbackModel.showKaraoke, ::updateKaraokeVisibility)
        collectImmediately(playbackModel.showCamera, ::updateCameraVisibility)
        
        // Karaoke state observers
        collectImmediately(playbackModel.vocalsEnabled, ::updateVocalsState)
        collectImmediately(playbackModel.accompanimentEnabled, ::updateAccompanimentState)
        collectImmediately(playbackModel.vocalsVolume) { volume ->
            binding.karaokeVocalsVolume?.value = volume.toFloat()
        }
        collectImmediately(playbackModel.accompanimentVolume) { volume ->
            binding.karaokeAccompanimentVolume?.value = volume.toFloat()
        }
        collectImmediately(playbackModel.hasKaraokeFiles, ::updateKaraokeFilesState)

        collectImmediately(playbackModel.lyrics, ::updateLyrics)
    }

    override fun onStart() {
        super.onStart()
        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onStop() {
        super.onStop()
        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() {
        if (binding == null || lastCoverWidth < 0) {
            return
        }
        // Hacky workaround for cover radius not being preserved in between sizing changes
        // (i.e split screen or landscape mode)
        // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
        // size to radically change, so we wait until it stabilizes and then force an image
        // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
        // did accidentally load the wrong image (with weird corner radius intended for bigger
        // covers) we can force it to reload.
        // If this breaks, it's fine since we also started a load as we normally did w/state
        // updates, so the cover will not break.
        val binding = requireBinding()
        val coverWidth = binding.playbackCover.width
        if (lastCoverWidth != coverWidth) {
            lastCoverWidth = coverWidth
        } else {
            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
            lastCoverWidth = -1
        }
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        stopCamera()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_show_lyrics) {
            playbackModel.toggleLyrics()
            return true
        } else if (item.itemId == R.id.action_show_karaoke) {
            playbackModel.toggleKaraoke()
            return true
        } else if (item.itemId == R.id.playback_camera_toggle) {
            playbackModel.toggleCamera()
            return true
        }

        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    override fun onSwipePrevious() {
        playbackModel.prev()
    }

    override fun onSwipeNext() {
        playbackModel.next()
    }

    override fun onStepBack() {
        playbackModel.stepBack()
    }

    override fun onStepForward() {
        playbackModel.stepForward()
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackCover.bind(song)
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        val binding = requireBinding()
        binding.playbackSeekBar?.positionDs = positionDs
        
        val positionMs = positionDs.dsToMs() + 200 // 200ms offset
        if (playbackModel.lyrics.value != null) {
            binding.playbackLyrics.setPosition(positionMs)
            if (!binding.playbackPlayPause.isActivated) {
                binding.playbackLyrics.startAnimation(false)
            }
        }
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        requireBinding().playbackRepeat.apply {
            setIconResource(repeatMode.icon)
            isActivated = repeatMode != RepeatMode.NONE
        }
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isActivated = isPlaying

        // START or STOP the high-frequency fluid timer
        val binding = requireBinding()
        if (isPlaying) {
            binding.playbackLyrics.startAnimation(true)
        } else {
            binding.playbackLyrics.stopAnimation()
        }
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isActivated = isShuffled
    }

    private fun updateLyricsVisibility(showLyrics: Boolean) {
        val binding = requireBinding()
        binding.playbackCover.visibility = if (showLyrics) View.INVISIBLE else View.VISIBLE
        binding.playbackLyrics.visibility = if (showLyrics) View.VISIBLE else View.GONE
        binding.playbackLyricsBackground.visibility = if (showLyrics) View.VISIBLE else View.GONE

        // Update menu item state if it exists
        binding.playbackToolbar.menu.findItem(R.id.action_show_lyrics)?.apply {
            // We use icon alpha to show "on/off" state for menu items usually
            val iconRes = if (showLyrics) R.drawable.ic_lyrics_on_24 else R.drawable.ic_lyrics_24
            setIcon(iconRes)
            icon?.alpha = if (showLyrics) 255 else 128
        }
    }

    private fun updateKaraokeVisibility(showKaraoke: Boolean) {
        val binding = requireBinding()
        binding.playbackKaraokeContainer?.visibility = if (showKaraoke) View.VISIBLE else View.GONE

        // Update menu item state if it exists
        binding.playbackToolbar.menu.findItem(R.id.action_show_karaoke)?.apply {
            val iconRes = if (showKaraoke) R.drawable.ic_karaoke_on_24 else R.drawable.ic_karaoke_24
            setIcon(iconRes)
            icon?.alpha = if (showKaraoke) 255 else 128
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            startCamera()
        } else {
            L.w("Camera or Audio permission denied by user")
            // Revert the UI state since we can't actually start the camera
            if (playbackModel.showCamera.value) {
                playbackModel.toggleCamera()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateCameraVisibility(showCamera: Boolean) {
        val binding = requireBinding() // Use requireBinding() instead of binding?

        if (showCamera) {
            val permissions = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )

            val allGranted = permissions.all {
                ContextCompat.checkSelfPermission(requireContext(), it) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                startCamera()
            } else {
                // 1. Request the permissions
                requestPermissionLauncher.launch(permissions)

                // 2. IMPORTANT: Do NOT toggle the model back yet.
                // Let the requestPermissionLauncher handle the result.
                // If you toggle here, the UI state and permission state get out of sync.
            }
        } else {
            stopCamera()
        }

        // Fix the menu icon update
        binding.playbackCameraToggle?.apply {
            val iconRes = if (showCamera) R.drawable.ic_camera_on_24 else R.drawable.ic_camera_24
            setIconResource(iconRes)
            icon?.alpha = if (showCamera) 255 else 128
        }
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startCamera() {
        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            this.cameraProvider = provider

            // VideoCapture use case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider?.unbindAll()
                // Binding only videoCapture (preview is null)
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, videoCapture)

                // Inline permission check to satisfy the IDE/Compiler
                if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    L.e("Cannot start recording: RECORD_AUDIO permission not granted in listener")
                }
            } catch (e: Exception) {
                L.e(e, "Use case binding failed")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        val name = "Auxio-Recording-${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")

            // Change from "Movies/Auxio-Captures" to "DCIM/Camera"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireContext().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        L.e("STARTED RECORDING!!!")

        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        L.d("Recording Started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            L.d(msg)
                        } else {
                            recording?.close()
                            recording = null
                            L.e("Video capture ends with error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun stopCamera() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        L.d("Camera hardware released")
        // Unbind camera if needed, or just let lifecycle handle it
    }

    private fun updateVocalsState(enabled: Boolean) {
        val binding = requireBinding()
        binding.karaokeVocalsToggle?.icon?.alpha = if (enabled) 255 else 128
        binding.karaokeVocalsVolume?.alpha = if (enabled) 1.0f else 0.5f
        binding.karaokeVocalsVolume?.isEnabled = enabled
    }

    private fun updateAccompanimentState(enabled: Boolean) {
        val binding = requireBinding()
        binding.karaokeAccompanimentToggle?.icon?.alpha = if (enabled) 255 else 128
        binding.karaokeAccompanimentVolume?.alpha = if (enabled) 1.0f else 0.5f
        binding.karaokeAccompanimentVolume?.isEnabled = enabled
    }

    private fun updateKaraokeFilesState(hasFiles: Boolean) {
        val binding = requireBinding()
        binding.karaokeControls?.visibility = if (hasFiles) View.VISIBLE else View.GONE
        binding.karaokeEmptyText?.visibility = if (hasFiles) View.GONE else View.VISIBLE
    }

    private fun updateLyrics(lyrics: TimedLyrics?) {
        val binding = requireBinding()
        if (lyrics != null) {
            binding.playbackLyrics.setTimedLyrics(lyrics)
            updatePosition(playbackModel.positionDs.value)
        } else {
            binding.playbackLyrics.setTimedLyrics(null)
            if (playbackModel.showLyrics.value) {
                binding.playbackLyrics.text = "No lyrics found."
            }
        }
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }
}
