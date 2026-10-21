package com.scariflabs.horus

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for Horus remote terminal access
 * Connects to Cloudflare Worker relay server
 */
class HorusWebSocketClient(
    private val onConnect: () -> Unit,
    private val onDisconnect: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    
    private var sessionId: String? = null
    private var token: String? = null
    private var serverUrl: String? = null
    
    companion object {
        private const val TAG = "HorusWebSocket"
        private const val DEFAULT_RELAY_URL = "https://horus-relay.huorty4.workers.dev"
    }
    
    /**
     * Connect to relay server as host (phone)
     * 
     * @param relayUrl Worker URL (default: https://horus-relay.huorty4.workers.dev)
     * @param sessionId Unique session ID (8-64 chars)
     * @param token Authentication token
     */
    fun connectAsHost(
        relayUrl: String = DEFAULT_RELAY_URL,
        sessionId: String,
        token: String
    ) {
        this.sessionId = sessionId
        this.token = token
        this.serverUrl = relayUrl
        
        val wsUrl = buildWsUrl(relayUrl, "host", sessionId, token)
        connect(wsUrl)
    }
    
    /**
     * Connect to relay server as client (browser)
     */
    fun connectAsClient(
        relayUrl: String = DEFAULT_RELAY_URL,
        sessionId: String,
        token: String
    ) {
        this.sessionId = sessionId
        this.token = token
        this.serverUrl = relayUrl
        
        val wsUrl = buildWsUrl(relayUrl, "client", sessionId, token)
        connect(wsUrl)
    }
    
    private fun buildWsUrl(baseUrl: String, role: String, sessionId: String, token: String): String {
        val secureUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        return "$secureUrl/$role?id=$sessionId&token=$token"
    }
    
    private fun connect(url: String) {
        Log.d(TAG, "Connecting to $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                onConnect()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary: ${bytes.size} bytes")
                onMessage(bytes.utf8())
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $code - $reason")
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Closed: $code - $reason")
                onDisconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Error: ${t.message}", t)
                onError(t.message ?: "Unknown error")
                onDisconnect()
            }
        })
    }
    
    /**
     * Send terminal output to connected clients
     */
    fun sendOutput(output: String) {
        val message = JSONObject().apply {
            put("type", "output")
            put("data", output)
        }.toString()
        
        send(message)
    }
    
    /**
     * Send command (from client to host)
     */
    fun sendCommand(command: String) {
        val message = JSONObject().apply {
            put("type", "command")
            put("data", command)
        }.toString()
        
        send(message)
    }
    
    /**
     * Send raw message
     */
    fun send(message: String) {
        webSocket?.send(message) ?: run {
            Log.w(TAG, "WebSocket not connected")
        }
    }
    
    /**
     * Send binary data
     */
    fun sendBinary(data: ByteArray) {
        webSocket?.send(data.toByteString()) ?: run {
            Log.w(TAG, "WebSocket not connected")
        }
    }
    
    /**
     * Disconnect from server
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = webSocket != null
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val data = json.optString("data")
            
            when (type) {
                "command" -> {
                    // Command from client - execute in terminal
                    onMessage(text)
                }
                "output" -> {
                    // Output from host - display in client
                    onMessage(text)
                }
                "host_connected" -> {
                    Log.i(TAG, "Host connected")
                    onMessage(text)
                }
                "host_disconnected" -> {
                    Log.i(TAG, "Host disconnected")
                    onMessage(text)
                }
                "error" -> {
                    Log.e(TAG, "Server error: $data")
                    onError(data)
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                    onMessage(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
            onMessage(text)
        }
    }
}
