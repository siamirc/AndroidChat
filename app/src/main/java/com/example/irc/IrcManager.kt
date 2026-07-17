package com.example.irc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class IrcMessage(
    val sender: String,
    val text: String,
    val timestamp: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val isSystem: Boolean = false,
    val isSelf: Boolean = false
)

class IrcManager {
    private val TAG = "IrcManager"
    private val thaiCharset = try {
        Charset.forName("windows-874")
    } catch (e: Exception) {
        Charset.forName("UTF-8")
    }

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status = _status.asStateFlow()

    private val _messages = MutableStateFlow<List<IrcMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _users = MutableStateFlow<Set<String>>(emptySet())
    val users = _users.asStateFlow()

    private var currentNick = ""
    private var currentChannel = ""

    fun connect(server: String, port: Int, nick: String, channel: String) {
        if (_status.value != ConnectionStatus.DISCONNECTED) {
            disconnect()
        }

        _status.value = ConnectionStatus.CONNECTING
        _messages.value = listOf(IrcMessage("System", "Connecting to $server:$port...", isSystem = true))
        _users.value = emptySet()
        currentNick = nick
        currentChannel = if (channel.startsWith("#")) channel else "#$channel"

        connectionJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rawSocket = Socket(server, port)
                    socket = rawSocket
                    writer = BufferedWriter(OutputStreamWriter(rawSocket.getOutputStream(), thaiCharset))
                    reader = BufferedReader(InputStreamReader(rawSocket.getInputStream(), thaiCharset))

                    // Send connection handshake
                    sendRaw("NICK $currentNick")
                    sendRaw("USER $currentNick 0 * :ThaiIRC Client")
                }

                _status.value = ConnectionStatus.CONNECTED
                addSystemMessage("Connection established. Authenticating...")

                // Start reading loop
                readLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _status.value = ConnectionStatus.ERROR
                addSystemMessage("Error: ${e.localizedMessage ?: "Failed to connect"}")
                disconnect()
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null

        scope.launch(Dispatchers.IO) {
            try {
                if (writer != null && currentChannel.isNotEmpty()) {
                    sendRaw("PART $currentChannel :Goodbye")
                    sendRaw("QUIT :Leaving")
                }
                writer?.close()
                reader?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing connection", e)
            } finally {
                writer = null
                reader = null
                socket = null
                _status.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    fun sendMessage(text: String) {
        if (_status.value != ConnectionStatus.CONNECTED || writer == null || currentChannel.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            try {
                sendRaw("PRIVMSG $currentChannel :$text")
                _messages.update { it + IrcMessage(currentNick, text, isSelf = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                addSystemMessage("Failed to send message: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun readLoop() {
        val r = reader ?: return
        val privMsgRegex = Regex("""^:([^! ]+)(![^ ]+)? PRIVMSG ([^ ]+) :(.+)$""")
        val joinRegex = Regex("""^:([^! ]+)(![^ ]+)? JOIN :?([^ ]+)$""")
        val partRegex = Regex("""^:([^! ]+)(![^ ]+)? PART ([^ ]+)( :.*)?$""")
        val quitRegex = Regex("""^:([^! ]+)(![^ ]+)? QUIT( :.*)?$""")
        val nickChangeRegex = Regex("""^:([^! ]+)(![^ ]+)? NICK :?([^ ]+)$""")

        withContext(Dispatchers.IO) {
            try {
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val rawLine = line ?: break
                    Log.d(TAG, "IRC: $rawLine")

                    // Handle PING immediately
                    if (rawLine.startsWith("PING")) {
                        val payload = rawLine.substringAfter("PING")
                        sendRaw("PONG$payload")
                        continue
                    }

                    // Parse incoming message structures
                    when {
                        // Channel Messages (PRIVMSG)
                        privMsgRegex.matches(rawLine) -> {
                            val match = privMsgRegex.matchEntire(rawLine)!!
                            val sender = match.groupValues[1]
                            val target = match.groupValues[3]
                            val messageText = match.groupValues[4]

                            if (target.equals(currentChannel, ignoreCase = true)) {
                                _messages.update { it + IrcMessage(sender, messageText) }
                            }
                        }

                        // User Joins
                        joinRegex.matches(rawLine) -> {
                            val match = joinRegex.matchEntire(rawLine)!!
                            val nick = match.groupValues[1]
                            val chan = match.groupValues[3]

                            if (chan.equals(currentChannel, ignoreCase = true)) {
                                if (nick.equals(currentNick, ignoreCase = true)) {
                                    addSystemMessage("You joined $currentChannel")
                                } else {
                                    _users.update { it + nick }
                                    addSystemMessage("$nick has joined the channel")
                                }
                            }
                        }

                        // User Parts
                        partRegex.matches(rawLine) -> {
                            val match = partRegex.matchEntire(rawLine)!!
                            val nick = match.groupValues[1]
                            val chan = match.groupValues[3]

                            if (chan.equals(currentChannel, ignoreCase = true)) {
                                _users.update { it - nick }
                                addSystemMessage("$nick has left the channel")
                            }
                        }

                        // User Quits
                        quitRegex.matches(rawLine) -> {
                            val match = quitRegex.matchEntire(rawLine)!!
                            val nick = match.groupValues[1]
                            _users.update { it - nick }
                            addSystemMessage("$nick has quit IRC")
                        }

                        // User Nick Change
                        nickChangeRegex.matches(rawLine) -> {
                            val match = nickChangeRegex.matchEntire(rawLine)!!
                            val oldNick = match.groupValues[1]
                            val newNick = match.groupValues[3]

                            if (oldNick.equals(currentNick, ignoreCase = true)) {
                                currentNick = newNick
                                addSystemMessage("Your nickname is now $newNick")
                            } else {
                                _users.update { (it - oldNick) + newNick }
                                addSystemMessage("$oldNick is now known as $newNick")
                            }
                        }

                        // Welcome Messages, MOTD or Server Numerics
                        else -> {
                            val parts = rawLine.split(" ")
                            if (parts.size >= 2) {
                                val code = parts[1]
                                when (code) {
                                    "001", "002", "003", "004", "251", "252", "254", "255" -> {
                                        // Display welcome messages
                                        val welcomeText = rawLine.substringAfter(" :", rawLine)
                                        addSystemMessage(welcomeText)
                                    }
                                    "372", "375" -> {
                                        // MOTD content
                                        val motdText = rawLine.substringAfter(" :", "")
                                        if (motdText.isNotEmpty()) {
                                            addSystemMessage(motdText)
                                        }
                                    }
                                    "376", "422" -> {
                                        // End of MOTD, safe to join channel
                                        addSystemMessage("Authentication complete. Joining $currentChannel...")
                                        sendRaw("JOIN $currentChannel")
                                    }
                                    "353" -> {
                                        // NAMES list: :server 353 nick = #channel :user1 user2 @user3
                                        val listStr = rawLine.substringAfter(" :", "")
                                        if (listStr.isNotEmpty()) {
                                            val parsedUsers = listStr.split(" ").map {
                                                // strip prefixes like @, +, %
                                                it.trim().removePrefix("@").removePrefix("+").removePrefix("%").removePrefix("~").removePrefix("&")
                                            }.filter { it.isNotEmpty() }
                                            _users.update { it + parsedUsers }
                                        }
                                    }
                                    "366" -> {
                                        // End of NAMES list
                                        addSystemMessage("Channel users listed.")
                                    }
                                    "433" -> {
                                        // Nickname is already in use
                                        val suggestedNick = currentNick + "_" + (10..99).random()
                                        addSystemMessage("Nickname $currentNick is already in use. Retrying with $suggestedNick...")
                                        currentNick = suggestedNick
                                        sendRaw("NICK $currentNick")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reader loop exception", e)
                addSystemMessage("Connection lost: ${e.localizedMessage}")
            } finally {
                _status.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    private fun sendRaw(line: String) {
        val w = writer ?: return
        try {
            Log.d(TAG, "Sending: $line")
            w.write("$line\r\n")
            w.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw line", e)
        }
    }

    private fun addSystemMessage(text: String) {
        _messages.update { it + IrcMessage("System", text, isSystem = true) }
    }
}
