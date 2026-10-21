package com.scariflabs.horus

import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native module for WebSocket access
 */
class HorusWebSocketModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    
    private var webSocketClient: HorusWebSocketClient? = null
    
    companion object {
        private const val TAG = "HorusWebSocketModule"
        const val NAME = "HorusWebSocketModule"
    }
    
    override fun getName(): String = NAME
    
    /**
     * Connect to relay server as host (phone)
     */
    @ReactMethod
    fun connectAsHost(
        relayUrl: String,
        sessionId: String,
        token: String,
        promise: Promise
    ) {
        try {
            disconnect()
            
            webSocketClient = HorusWebSocketClient(
                onConnect = {
                    sendEvent("onWebSocketConnect", null)
                },
                onDisconnect = {
                    sendEvent("onWebSocketDisconnect", null)
                },
                onMessage = { message ->
                    sendEvent("onWebSocketMessage", message)
                },
                onError = { error ->
                    sendEvent("onWebSocketError", error)
                }
            )
            
            webSocketClient?.connectAsHost(relayUrl, sessionId, token)
            promise.resolve("Connecting to $relayUrl as host")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            promise.reject("CONNECTION_ERROR", e.message)
        }
    }
    
    /**
     * Connect to relay server as client (browser)
     */
    @ReactMethod
    fun connectAsClient(
        relayUrl: String,
        sessionId: String,
        token: String,
        promise: Promise
    ) {
        try {
            disconnect()
            
            webSocketClient = HorusWebSocketClient(
                onConnect = {
                    sendEvent("onWebSocketConnect", null)
                },
                onDisconnect = {
                    sendEvent("onWebSocketDisconnect", null)
                },
                onMessage = { message ->
                    sendEvent("onWebSocketMessage", message)
                },
                onError = { error ->
                    sendEvent("onWebSocketError", error)
                }
            )
            
            webSocketClient?.connectAsClient(relayUrl, sessionId, token)
            promise.resolve("Connecting to $relayUrl as client")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            promise.reject("CONNECTION_ERROR", e.message)
        }
    }
    
    /**
     * Send terminal output
     */
    @ReactMethod
    fun sendOutput(data: String, promise: Promise) {
        try {
            webSocketClient?.sendOutput(data) ?: run {
                promise.reject("NOT_CONNECTED", "WebSocket not connected")
                return
            }
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SEND_ERROR", e.message)
        }
    }
    
    /**
     * Send command
     */
    @ReactMethod
    fun sendCommand(command: String, promise: Promise) {
        try {
            webSocketClient?.sendCommand(command) ?: run {
                promise.reject("NOT_CONNECTED", "WebSocket not connected")
                return
            }
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SEND_ERROR", e.message)
        }
    }
    
    /**
     * Disconnect
     */
    @ReactMethod
    fun disconnect(promise: Promise) {
        try {
            disconnect()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("DISCONNECT_ERROR", e.message)
        }
    }
    
    /**
     * Check if connected
     */
    @ReactMethod
    fun isConnected(promise: Promise) {
        promise.resolve(webSocketClient?.isConnected() ?: false)
    }
    
    private fun disconnect() {
        webSocketClient?.disconnect()
        webSocketClient = null
    }
    
    private fun sendEvent(eventName: String, params: Any?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
}
