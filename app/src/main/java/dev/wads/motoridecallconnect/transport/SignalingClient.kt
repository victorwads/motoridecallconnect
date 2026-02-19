package dev.wads.motoridecallconnect.transport

import android.util.Log
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class SignalingClient(private val listener: SignalingListener) {

    private val connectExecutor = Executors.newSingleThreadExecutor()
    private val socketLock = Any()
    private val writeLock = Any()

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var writer: PrintWriter? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var isClosed = false

    interface SignalingListener {
        fun onPeerConnected(isInitiator: Boolean)
        fun onPeerDisconnected()
        fun onPeerInfoReceived(name: String)
        fun onOfferReceived(description: String)
        fun onAnswerReceived(description: String)
        fun onIceCandidateReceived(candidate: String)
        fun onPeerTransmissionStateReceived(isTransmitting: Boolean)
        fun onTripStatusReceived(
            active: Boolean,
            tripId: String? = null,
            hostUid: String? = null,
            tripPath: String? = null
        )
        fun onSignalingError(error: Throwable)
    }

    fun startServer(port: Int) {
        connectExecutor.execute {
            try {
                val localServerSocket = ServerSocket(port)
                synchronized(socketLock) {
                    if (isClosed) {
                        localServerSocket.close()
                        return@execute
                    }
                    serverSocket = localServerSocket
                }
                Log.d(TAG, "Signaling server started on port $port")

                val acceptedSocket = localServerSocket.accept()
                attachSocket(acceptedSocket, isInitiator = false)
            } catch (e: Exception) {
                if (!isClosed) {
                    Log.e(TAG, "Error starting signaling server", e)
                    listener.onSignalingError(e)
                    close()
                }
            }
        }
    }

    fun connectToPeer(address: InetAddress, port: Int, network: android.net.Network? = null) {
        connectToPeer(listOf(address), port, listOf(network))
    }

    fun connectToPeer(addresses: List<InetAddress>, port: Int, network: android.net.Network? = null) {
        connectToPeer(addresses, port, listOf(network))
    }

    fun connectToPeer(
        addresses: List<InetAddress>,
        port: Int,
        networks: List<android.net.Network?> = listOf(null)
    ) {
        connectExecutor.execute {
            if (addresses.isEmpty()) {
                val error = IllegalArgumentException("No candidate peer addresses provided.")
                listener.onSignalingError(error)
                return@execute
            }

            val prioritizedNetworks = networks
                .ifEmpty { listOf(null) }
                .distinct()

            var lastError: Exception? = null
            for (network in prioritizedNetworks) {
                for (address in addresses) {
                    try {
                        val connectedSocket = createConnectedSocket(address, port, network)
                        if (!isClosed) {
                            val networkLabel = network?.toString() ?: "default"
                            Log.i(TAG, "Connected to peer using ${address.hostAddress}:$port on network=$networkLabel")
                            attachSocket(connectedSocket, isInitiator = true)
                        } else {
                            connectedSocket.close()
                        }
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        val networkLabel = network?.toString() ?: "default"
                        Log.w(
                            TAG,
                            "Failed to connect to ${address.hostAddress}:$port on network=$networkLabel",
                            e
                        )
                    }
                }
            }

            if (!isClosed) {
                val finalError = IllegalStateException(
                    "Unable to connect to peer on any candidate IP: ${
                        addresses.joinToString { it.hostAddress ?: "unknown" }
                    }",
                    lastError
                )
                Log.e(TAG, "Error connecting to peer using candidates", finalError)
                listener.onSignalingError(finalError)
                close()
            }
        }
    }

    private fun createConnectedSocket(
        address: InetAddress,
        port: Int,
        network: android.net.Network?
    ): Socket {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.connect(InetSocketAddress(address, port), SOCKET_CONNECT_TIMEOUT_MS)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun attachSocket(socket: Socket, isInitiator: Boolean) {
        synchronized(socketLock) {
            if (isClosed) {
                socket.close()
                return
            }
            clientSocket = socket
            writer = PrintWriter(socket.getOutputStream(), true)
        }

        Log.d(TAG, "Signaling peer connected.")
        listener.onPeerConnected(isInitiator)
        startReaderLoop(socket)
    }

    private fun startReaderLoop(socket: Socket) {
        val thread = Thread(
            {
                try {
                    handleConnection(socket)
                } catch (e: Exception) {
                    if (!isClosed) {
                        Log.e(TAG, "Error handling connection", e)
                        listener.onSignalingError(e)
                    }
                } finally {
                    if (!isClosed) {
                        listener.onPeerDisconnected()
                        close()
                    }
                }
            },
            "SignalingReader"
        )
        readerThread = thread
        thread.start()
    }

    private fun handleConnection(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        while (!Thread.currentThread().isInterrupted) {
            val message = reader.readLine() ?: break
            when {
                message.startsWith("NAME:") -> listener.onPeerInfoReceived(message.substringAfter("NAME:"))
                message.startsWith("OFFER64:") -> {
                    val decoded = decodePayload(message.substringAfter("OFFER64:"))
                    listener.onOfferReceived(decoded)
                }
                message.startsWith("OFFER:") -> listener.onOfferReceived(message.substringAfter("OFFER:"))
                message.startsWith("ANSWER64:") -> {
                    val decoded = decodePayload(message.substringAfter("ANSWER64:"))
                    listener.onAnswerReceived(decoded)
                }
                message.startsWith("ANSWER:") -> listener.onAnswerReceived(message.substringAfter("ANSWER:"))
                message.startsWith("ICE64:") -> {
                    val parts = message.split(":", limit = 4)
                    if (parts.size == 4) {
                        val mid = parts[1]
                        val index = parts[2]
                        val sdp = decodePayload(parts[3])
                        listener.onIceCandidateReceived("$mid:$index:$sdp")
                    }
                }
                message.startsWith("ICE:") -> listener.onIceCandidateReceived(message.substringAfter("ICE:"))
                message.startsWith("TX:") -> {
                    val raw = message.substringAfter("TX:").trim().lowercase()
                    val transmitting = raw == "on" || raw == "1" || raw == "true"
                    listener.onPeerTransmissionStateReceived(transmitting)
                }
                message.startsWith("TRIP:START") -> {
                    val parts = message.split(":", limit = 5)
                    val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                    val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                    listener.onTripStatusReceived(true, tripId, hostUid, tripPath)
                }
                message.startsWith("TRIP:STOP") -> {
                    val parts = message.split(":", limit = 5)
                    val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                    val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                    listener.onTripStatusReceived(false, tripId, hostUid, tripPath)
                }
            }
            Log.d(TAG, "Received message: $message")
        }
    }

    private fun decodePayload(encoded: String): String {
        return String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    }

    fun sendMessage(message: String) {
        try {
            synchronized(writeLock) {
                val currentWriter = writer
                if (currentWriter == null) {
                    Log.w(TAG, "sendMessage dropped (no active socket): $message")
                    return
                }
                currentWriter.println(message)
                if (currentWriter.checkError()) {
                    throw IllegalStateException("Failed to write signaling message")
                }
            }
        } catch (e: Exception) {
            if (!isClosed) {
                Log.e(TAG, "Error sending signaling message", e)
                listener.onSignalingError(e)
                close()
            }
        }
    }

    fun close() {
        synchronized(socketLock) {
            if (isClosed) return
            isClosed = true
        }

        try {
            readerThread?.interrupt()
            writer?.close()
            clientSocket?.close()
            serverSocket?.close()
            connectExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing signaling client", e)
        }
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 2_500
    }
}
