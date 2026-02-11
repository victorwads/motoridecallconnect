package dev.wads.motoridecallconnect.transport

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class SignalingClient(private val listener: SignalingListener) {

    private val executor = Executors.newSingleThreadExecutor()
    private var clientSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var writer: PrintWriter? = null

    interface SignalingListener {
        fun onOfferReceived(description: String)
        fun onAnswerReceived(description: String)
        fun onIceCandidateReceived(candidate: String)
    }

    fun startServer(port: Int) {
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Signaling server started on port $port")
                clientSocket = serverSocket?.accept()
                clientSocket?.getOutputStream()?.let {
                    writer = PrintWriter(it, true)
                }
                Log.d(TAG, "Signaling client connected.")
                clientSocket?.let { handleConnection(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting signaling server", e)
            }
        }
    }

    fun connectToPeer(address: InetAddress, port: Int) {
        executor.execute {
            try {
                clientSocket = Socket(address, port)
                clientSocket?.getOutputStream()?.let {
                    writer = PrintWriter(it, true)
                }
                Log.d(TAG, "Connected to signaling peer.")
                clientSocket?.let { handleConnection(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to peer", e)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        try {
            while (true) {
                val message = reader.readLine() ?: break
                when {
                    message.startsWith("OFFER:") -> listener.onOfferReceived(message.substringAfter("OFFER:"))
                    message.startsWith("ANSWER:") -> listener.onAnswerReceived(message.substringAfter("ANSWER:"))
                    message.startsWith("ICE:") -> listener.onIceCandidateReceived(message.substringAfter("ICE:"))
                }
                Log.d(TAG, "Received message: $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection", e)
        }
    }

    fun sendMessage(message: String) {
        executor.execute {
            writer?.println(message)
        }
    }

    fun close() {
        try {
            writer?.close()
            clientSocket?.close()
            serverSocket?.close()
            executor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing signaling client", e)
        }
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}