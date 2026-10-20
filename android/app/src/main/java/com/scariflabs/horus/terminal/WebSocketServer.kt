package com.scariflabs.horus.terminal

import android.util.Log
import okhttp3.*
import okhttp3.internal.ws.RealWebSocket
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket Server for Horus Terminal
 * Allows remote access without USB/ADB
 */
class HorusWebSocketServer(
  private val onCommand: (String) -> Unit,
  private val onConnect: () -> Unit,
  private val onDisconnect: () -> Unit
) : WebSocketListener() {

  private val client = OkHttpClient.Builder()
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var tunnelUrl: String? = null

  companion object {
    private const val TAG = "HorusWebSocket"
    private const val RECONNECT_DELAY_MS = 5000L
    private const val MAX_RECONNECT_ATTEMPTS = 10
  }

  /**
   * Connect to relay server
   * @param serverUrl WebSocket relay server URL (e.g., "wss://horus-relay.example.com")
   * @param deviceId Unique device identifier
   */
  fun connect(serverUrl: String, deviceId: String) {
    val url = HttpUrl.parse(serverUrl)?.newBuilder()
      ?.addQueryParameter("device", deviceId)
      ?.addQueryParameter("type", "host")
      ?.build()

    if (url == null) {
      Log.e(TAG, "Invalid server URL: $serverUrl")
      return
    }

    val request = Request.Builder()
      .url(url)
      .build()

    webSocket = client.newWebSocket(request, this)
    Log.i(TAG, "Connecting to $serverUrl as device $deviceId")
  }

  /**
   * Connect via tunnel service (Cloudflare Tunnel, ngrok, etc.)
   */
  fun connectViaTunnel(tunnelService: String, deviceId: String) {
    when (tunnelService) {
      "cloudflare" -> {
        // Cloudflare Tunnel integration
        connect("wss://trycloudflare.com", deviceId)
      }
      "ngrok" -> {
        // ngrok integration
        connect("wss://tunnel.ngrok.io", deviceId)
      }
      else -> {
        Log.e(TAG, "Unknown tunnel service: $tunnelService")
      }
    }
  }

  /**
   * Send terminal output to connected client
   */
  fun sendOutput(data: String) {
    val json = JSONObject().apply {
      put("type", "output")
      put("data", data)
      put("timestamp", System.currentTimeMillis())
    }
    webSocket?.send(json.toString())
  }

  /**
   * Send binary data
   */
  fun sendBinary(data: ByteString) {
    webSocket?.send(data)
  }

  /**
   * Close connection
   */
  fun disconnect() {
    webSocket?.close(1000, "Disconnecting")
    webSocket = null
  }

  override fun onOpen(webSocket: WebSocket, response: Response) {
    Log.i(TAG, "WebSocket connection opened")
    onConnect()

    // Send initial device info
    val json = JSONObject().apply {
      put("type", "device_info")
      put("model", android.os.Build.MODEL)
      put("version", android.os.Build.VERSION.SDK_INT)
    }
    webSocket.send(json.toString())
  }

  override fun onMessage(webSocket: WebSocket, text: String) {
    Log.d(TAG, "Received message: ${text.take(100)}")

    try {
      val json = JSONObject(text)
      when (json.getString("type")) {
        "command" -> {
          val command = json.getString("data")
          onCommand(command)
        }
        "input" -> {
          val input = json.getString("data")
          onCommand(input)
        }
        "resize" -> {
          val cols = json.getInt("cols")
          val rows = json.getInt("rows")
          // Handle terminal resize
          Log.d(TAG, "Terminal resize: ${cols}x${rows}")
        }
        "ping" -> {
          // Respond with pong
          val pong = JSONObject().apply {
            put("type", "pong")
            put("timestamp", System.currentTimeMillis())
          }
          webSocket.send(pong.toString())
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing message: ${e.message}")
      // Treat as raw command
      onCommand(text)
    }
  }

  override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    Log.d(TAG, "Received binary message: ${bytes.size} bytes")
    // Handle binary data
    onCommand(bytes.utf8())
  }

  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    Log.i(TAG, "WebSocket closing: $code - $reason")
  }

  override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
    Log.i(TAG, "WebSocket closed: $code - $reason")
    onDisconnect()
  }

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    Log.e(TAG, "WebSocket failure: ${t.message}")
    onDisconnect()

    // Attempt reconnection
    if (response?.code != 1000) {
      Log.i(TAG, "Attempting reconnection in ${RECONNECT_DELAY_MS}ms")
      // TODO: Implement exponential backoff reconnection
    }
  }
}
