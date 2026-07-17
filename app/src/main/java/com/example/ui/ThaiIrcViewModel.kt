package com.example.ui

import androidx.lifecycle.ViewModel
import com.example.irc.ConnectionStatus
import com.example.irc.IrcManager
import com.example.irc.IrcMessage
import com.example.radio.PlaybackState
import com.example.radio.RadioManager
import com.example.radio.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThaiIrcViewModel : ViewModel() {
    val ircManager = IrcManager()
    val radioManager = RadioManager()

    // Form inputs
    private val _serverInput = MutableStateFlow("irc.thaiirc.com")
    val serverInput = _serverInput.asStateFlow()

    private val _portInput = MutableStateFlow("6667")
    val portInput = _portInput.asStateFlow()

    private val _nicknameInput = MutableStateFlow("ThaiGuest_${(1000..9999).random()}")
    val nicknameInput = _nicknameInput.asStateFlow()

    private val _channelInput = MutableStateFlow("#thaiirc")
    val channelInput = _channelInput.asStateFlow()

    private val _messageInput = MutableStateFlow("")
    val messageInput = _messageInput.asStateFlow()

    // Expose statuses from managers
    val ircStatus = ircManager.status
    val ircMessages = ircManager.messages
    val ircUsers = ircManager.users

    val radioStations = radioManager.stations
    val currentStation = radioManager.currentStation
    val playbackState = radioManager.playbackState
    val radioErrorMessage = radioManager.errorMessage

    fun updateServer(value: String) { _serverInput.value = value }
    fun updatePort(value: String) { _portInput.value = value }
    fun updateNickname(value: String) { _nicknameInput.value = value }
    fun updateChannel(value: String) { _channelInput.value = value }
    fun updateMessage(value: String) { _messageInput.value = value }

    fun connectIrc() {
        val portInt = _portInput.value.toIntOrNull() ?: 6667
        ircManager.connect(
            server = _serverInput.value,
            port = portInt,
            nick = _nicknameInput.value,
            channel = _channelInput.value
        )
    }

    fun disconnectIrc() {
        ircManager.disconnect()
    }

    fun sendIrcMessage() {
        val text = _messageInput.value.trim()
        if (text.isNotEmpty()) {
            ircManager.sendMessage(text)
            _messageInput.value = ""
        }
    }

    fun playRadio() {
        radioManager.play()
    }

    fun stopRadio() {
        radioManager.stop()
    }

    fun selectRadioStation(station: RadioStation) {
        radioManager.selectStation(station)
    }

    override fun onCleared() {
        super.onCleared()
        ircManager.disconnect()
        radioManager.stop()
    }
}
