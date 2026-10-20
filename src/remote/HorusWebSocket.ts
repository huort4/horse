import { NativeModules, NativeEventEmitter } from 'react-native';

const { HorusWebSocketModule } = NativeModules;
const eventEmitter = new NativeEventEmitter(HorusWebSocketModule);

export type WebSocketConfig = {
  serverUrl?: string;
  tunnelService?: 'cloudflare' | 'ngrok' | 'custom';
  deviceId: string;
};

export type TerminalOutput = {
  type: 'output' | 'error' | 'resize';
  data: string;
  timestamp: number;
};

export type TerminalInput = {
  type: 'command' | 'input' | 'resize';
  data?: string;
  cols?: number;
  rows?: number;
};

/**
 * WebSocket client for remote terminal access
 */
export class HorusWebSocket {
  private connected = false;
  private listeners: Array<() => void> = [];

  constructor() {
    // Listen to native events
    eventEmitter.addListener('onWebSocketMessage', (event: unknown) => this.handleMessage(event as { type: string; data: string }));
    eventEmitter.addListener('onWebSocketConnect', this.handleConnect.bind(this));
    eventEmitter.addListener('onWebSocketDisconnect', this.handleDisconnect.bind(this));
  }

  /**
   * Connect to relay server
   */
  async connect(config: WebSocketConfig): Promise<string> {
    if (config.tunnelService) {
      return await HorusWebSocketModule.connectViaTunnel(
        config.tunnelService,
        config.deviceId
      );
    } else if (config.serverUrl) {
      return await HorusWebSocketModule.connect(config.serverUrl, config.deviceId);
    } else {
      throw new Error('Either serverUrl or tunnelService must be provided');
    }
  }

  /**
   * Send terminal output to connected clients
   */
  sendOutput(data: string): void {
    if (!this.connected) {
      console.warn('WebSocket not connected');
      return;
    }
    HorusWebSocketModule.sendOutput(data);
  }

  /**
   * Send binary data
   */
  sendBinary(data: string): void {
    if (!this.connected) {
      console.warn('WebSocket not connected');
      return;
    }
    HorusWebSocketModule.sendBinary(data);
  }

  /**
   * Disconnect from server
   */
  disconnect(): void {
    HorusWebSocketModule.disconnect();
    this.connected = false;
  }

  /**
   * Check if connected
   */
  isConnected(): boolean {
    return this.connected;
  }

  /**
   * Add connection listener
   */
  onConnect(callback: () => void): () => void {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter(l => l !== callback);
    };
  }

  private handleConnect(): void {
    this.connected = true;
    this.listeners.forEach(l => l());
  }

  private handleDisconnect(): void {
    this.connected = false;
  }

  private handleMessage(event: { type: string; data: string }): void {
    // Handle incoming messages
    console.log('WebSocket message:', event);
  }
}

// Singleton instance
export const webSocket = new HorusWebSocket();
