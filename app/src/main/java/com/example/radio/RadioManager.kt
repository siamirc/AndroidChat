package com.example.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlaybackState {
    IDLE,
    PREPARING,
    PLAYING,
    ERROR
}

data class RadioStation(
    val name: String,
    val url: String,
    val description: String
)

class RadioManager {
    private val TAG = "RadioManager"

    val stations = listOf(
        RadioStation(
            name = "ThaiIRC Icecast",
            url = "http://icecast.thaiirc.com:8000/ices",
            description = "Main Icecast stream on port 8000"
        ),
        RadioStation(
            name = "ThaiIRC Radio",
            url = "http://radio.thaiirc.com:8002/ices",
            description = "Secondary Radio stream on port 8002"
        )
    )

    private var mediaPlayer: MediaPlayer? = null

    private val _currentStation = MutableStateFlow(stations[0])
    val currentStation = _currentStation.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState = _playbackState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun selectStation(station: RadioStation) {
        _currentStation.value = station
        if (_playbackState.value == PlaybackState.PLAYING || _playbackState.value == PlaybackState.PREPARING) {
            play()
        }
    }

    fun play() {
        stop()

        _playbackState.value = PlaybackState.PREPARING
        _errorMessage.value = null

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(_currentStation.value.url)
                
                setOnPreparedListener {
                    Log.d(TAG, "MediaPlayer prepared, starting stream")
                    it.start()
                    _playbackState.value = PlaybackState.PLAYING
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _playbackState.value = PlaybackState.ERROR
                    _errorMessage.value = "Failed to load stream (what=$what, extra=$extra)"
                    stop()
                    true
                }

                // Prepare asynchronously to keep the UI perfectly responsive
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaPlayer", e)
            _playbackState.value = PlaybackState.ERROR
            _errorMessage.value = e.localizedMessage ?: "Unknown initialization error"
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer", e)
        } finally {
            mediaPlayer = null
            _playbackState.value = PlaybackState.IDLE
        }
    }
}
