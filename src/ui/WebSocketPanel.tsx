import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  TextInput,
  Alert,
  NativeEventEmitter,
  NativeModules,
} from 'react-native';

const { HorusWebSocketModule } = NativeModules;
const eventEmitter = new NativeEventEmitter(HorusWebSocketModule);

type Props = {
  onSessionStart?: () => void;
  onSessionEnd?: () => void;
};

export function WebSocketPanel({ onSessionStart, onSessionEnd }: Props) {
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [relayUrl, setRelayUrl] = useState('https://horus-relay.huorty4.workers.dev');
  const [sessionId, setSessionId] = useState('');
  const [token, setToken] = useState('');

  useEffect(() => {
    // Generate random session ID
    setSessionId('horus-' + Math.random().toString(36).substring(2, 15));

    // Listen to events
    const connectSub = eventEmitter.addListener('onWebSocketConnect', () => {
      setConnected(true);
      setConnecting(false);
      onSessionStart?.();
    });

    const disconnectSub = eventEmitter.addListener('onWebSocketDisconnect', () => {
      setConnected(false);
      setConnecting(false);
      onSessionEnd?.();
    });

    const messageSub = eventEmitter.addListener('onWebSocketMessage', (event) => {
      console.log('WebSocket message:', event);
    });

    const errorSub = eventEmitter.addListener('onWebSocketError', (error) => {
      Alert.alert('Error', String(error));
      setConnecting(false);
    });

    return () => {
      connectSub.remove();
      disconnectSub.remove();
      messageSub.remove();
      errorSub.remove();
    };
  }, [onSessionStart, onSessionEnd]);

  const handleConnect = async () => {
    if (!token || token.length < 16) {
      Alert.alert('Error', 'Token must be at least 16 characters');
      return;
    }

    if (!sessionId || sessionId.length < 8) {
      Alert.alert('Error', 'Session ID must be at least 8 characters');
      return;
    }

    setConnecting(true);

    try {
      await HorusWebSocketModule.connectAsHost(relayUrl, sessionId, token);
    } catch (error: any) {
      Alert.alert('Connection Failed', error.message);
      setConnecting(false);
    }
  };

  const handleDisconnect = async () => {
    try {
      await HorusWebSocketModule.disconnect();
      setConnected(false);
    } catch (error: any) {
      Alert.alert('Error', error.message);
    }
  };

  if (connected) {
    return (
      <View style={styles.container}>
        <View style={styles.statusBar}>
          <View style={[styles.statusDot, styles.statusConnected]} />
          <Text style={styles.statusText}>Connected</Text>
        </View>

        <View style={styles.infoBox}>
          <Text style={styles.infoTitle}>Connection Details:</Text>
          <Text style={styles.infoText}>Session ID: {sessionId}</Text>
          <Text style={styles.infoText}>
            Connect from browser:
          </Text>
          <Text style={styles.infoCode}>
            wss://horus-relay.huorty4.workers.dev/client?id={sessionId}&token=YOUR_TOKEN
          </Text>
        </View>

        <TouchableOpacity style={styles.disconnectButton} onPress={handleDisconnect}>
          <Text style={styles.disconnectButtonText}>Disconnect</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Remote Access</Text>
      <Text style={styles.subtitle}>Access your terminal from anywhere</Text>

      <View style={styles.field}>
        <Text style={styles.label}>Relay Server URL</Text>
        <TextInput
          style={styles.input}
          value={relayUrl}
          onChangeText={setRelayUrl}
          placeholder="https://your-worker.workers.dev"
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      <View style={styles.field}>
        <Text style={styles.label}>Session ID</Text>
        <TextInput
          style={styles.input}
          value={sessionId}
          onChangeText={setSessionId}
          placeholder="horus-xxxxxx"
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      <View style={styles.field}>
        <Text style={styles.label}>Authentication Token</Text>
        <TextInput
          style={styles.input}
          value={token}
          onChangeText={setToken}
          placeholder="Your relay token"
          secureTextEntry
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      <TouchableOpacity
        style={[styles.button, connecting && styles.buttonDisabled]}
        onPress={handleConnect}
        disabled={connecting}
      >
        <Text style={styles.buttonText}>
          {connecting ? 'Connecting...' : 'Connect as Host'}
        </Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 16,
    backgroundColor: '#0a0a0a',
    borderRadius: 8,
  },
  statusBar: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  statusDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 8,
  },
  statusConnected: {
    backgroundColor: '#00ff9f',
  },
  statusText: {
    color: '#e0e0e0',
    fontSize: 16,
    fontWeight: 'bold',
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#00ff9f',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 14,
    color: '#999',
    marginBottom: 20,
  },
  field: {
    marginBottom: 16,
  },
  label: {
    color: '#999',
    fontSize: 12,
    marginBottom: 6,
  },
  input: {
    backgroundColor: '#1a1a1a',
    color: '#fff',
    padding: 12,
    borderRadius: 6,
    fontSize: 14,
    borderWidth: 1,
    borderColor: '#333',
  },
  button: {
    backgroundColor: '#00ff9f',
    padding: 14,
    borderRadius: 6,
    marginTop: 8,
    alignItems: 'center',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: '#000',
    fontWeight: 'bold',
    fontSize: 16,
  },
  disconnectButton: {
    backgroundColor: '#ff4444',
    padding: 14,
    borderRadius: 6,
    marginTop: 16,
    alignItems: 'center',
  },
  disconnectButtonText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
  infoBox: {
    marginTop: 16,
    padding: 12,
    backgroundColor: '#1a1a1a',
    borderRadius: 6,
    borderLeftWidth: 3,
    borderLeftColor: '#00ff9f',
  },
  infoTitle: {
    color: '#00ff9f',
    fontWeight: 'bold',
    marginBottom: 8,
  },
  infoText: {
    color: '#999',
    fontSize: 12,
    marginBottom: 4,
  },
  infoCode: {
    color: '#00ff9f',
    fontSize: 11,
    fontFamily: 'monospace',
    marginTop: 4,
  },
});
