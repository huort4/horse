import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  TextInput,
  Alert,
  Clipboard,
} from 'react-native';
import { webSocket, WebSocketConfig } from '../remote/HorusWebSocket';
import { uiColors } from './brand';

type Props = {
  onSessionStart?: () => void;
  onSessionEnd?: () => void;
};

export function WebSocketPanel({ onSessionStart, onSessionEnd }: Props) {
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [deviceId, setDeviceId] = useState('');
  const [connectionUrl, setConnectionUrl] = useState('');
  const [tunnelService, setTunnelService] = useState<'cloudflare' | 'ngrok'>('cloudflare');

  useEffect(() => {
    // Generate device ID
    const generateDeviceId = async () => {
      const id = `horus-${Date.now().toString(36)}`;
      setDeviceId(id);
    };
    generateDeviceId();

    // Listen to connection events
    const unsubscribe = webSocket.onConnect(() => {
      setConnected(true);
      setConnecting(false);
      onSessionStart?.();
    });

    return () => {
      unsubscribe();
    };
  }, [onSessionStart]);

  const handleConnect = useCallback(async () => {
    if (connecting) return;

    setConnecting(true);
    try {
      const config: WebSocketConfig = {
        tunnelService,
        deviceId,
      };

      const url = await webSocket.connect(config);
      setConnectionUrl(url);

      Alert.alert(
        'Connected!',
        `Access your terminal at:\n${url}`,
        [
          { text: 'Copy URL', onPress: () => Clipboard.setString(url) },
          { text: 'OK' }
        ]
      );
    } catch (error) {
      Alert.alert('Connection Failed', String(error));
      setConnecting(false);
    }
  }, [connecting, deviceId, tunnelService]);

  const handleDisconnect = useCallback(() => {
    webSocket.disconnect();
    setConnected(false);
    setConnectionUrl('');
    onSessionEnd?.();
  }, [onSessionEnd]);

  const copyConnectionUrl = useCallback(() => {
    if (connectionUrl) {
      Clipboard.setString(connectionUrl);
      Alert.alert('Copied!', 'Connection URL copied to clipboard');
    }
  }, [connectionUrl]);

  if (connected) {
    return (
      <View style={styles.container}>
        <View style={styles.statusBar}>
          <View style={[styles.statusDot, styles.statusConnected]} />
          <Text style={styles.statusText}>Connected</Text>
        </View>

        <TouchableOpacity style={styles.urlButton} onPress={copyConnectionUrl}>
          <Text style={styles.urlLabel}>Connection URL:</Text>
          <Text style={styles.urlText}>{connectionUrl}</Text>
        </TouchableOpacity>

        <Text style={styles.helpText}>
          Open the URL above in a browser to access your terminal
        </Text>

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
        <Text style={styles.label}>Device ID</Text>
        <TextInput
          style={styles.input}
          value={deviceId}
          onChangeText={setDeviceId}
          placeholder="horus-xxxxxx"
          autoCapitalize="none"
        />
      </View>

      <View style={styles.field}>
        <Text style={styles.label}>Tunnel Service</Text>
        <View style={styles.tunnelOptions}>
          <TouchableOpacity
            style={[
              styles.tunnelButton,
              tunnelService === 'cloudflare' && styles.tunnelButtonActive,
            ]}
            onPress={() => setTunnelService('cloudflare')}
          >
            <Text
              style={[
                styles.tunnelButtonText,
                tunnelService === 'cloudflare' && styles.tunnelButtonTextActive,
              ]}
            >
              Cloudflare
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.tunnelButton,
              tunnelService === 'ngrok' && styles.tunnelButtonActive,
            ]}
            onPress={() => setTunnelService('ngrok')}
          >
            <Text
              style={[
                styles.tunnelButtonText,
                tunnelService === 'ngrok' && styles.tunnelButtonTextActive,
              ]}
            >
              ngrok
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      <TouchableOpacity
        style={[
          styles.connectButton,
          connecting && styles.connectButtonDisabled,
        ]}
        onPress={handleConnect}
        disabled={connecting}
      >
        <Text style={styles.connectButtonText}>
          {connecting ? 'Connecting...' : 'Connect'}
        </Text>
      </TouchableOpacity>

      <Text style={styles.helpText}>
        This will create a secure tunnel to your device using {tunnelService}.
        You can then access your terminal from any web browser.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 16,
    backgroundColor: uiColors.background,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    color: uiColors.text,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 14,
    color: uiColors.textSecondary,
    marginBottom: 24,
  },
  statusBar: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8,
  },
  statusConnected: {
    backgroundColor: '#4CAF50',
  },
  statusText: {
    fontSize: 16,
    color: uiColors.text,
  },
  field: {
    marginBottom: 16,
  },
  label: {
    fontSize: 14,
    color: uiColors.textSecondary,
    marginBottom: 8,
  },
  input: {
    borderWidth: 1,
    borderColor: uiColors.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
    color: uiColors.text,
    backgroundColor: uiColors.surface,
  },
  tunnelOptions: {
    flexDirection: 'row',
    gap: 8,
  },
  tunnelButton: {
    flex: 1,
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: uiColors.border,
    alignItems: 'center',
  },
  tunnelButtonActive: {
    backgroundColor: uiColors.accent,
    borderColor: uiColors.accent,
  },
  tunnelButtonText: {
    fontSize: 14,
    color: uiColors.text,
  },
  tunnelButtonTextActive: {
    color: '#FFFFFF',
    fontWeight: '600',
  },
  connectButton: {
    backgroundColor: uiColors.accent,
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
    marginBottom: 16,
  },
  connectButtonDisabled: {
    opacity: 0.6,
  },
  connectButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#FFFFFF',
  },
  disconnectButton: {
    backgroundColor: uiColors.error,
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 16,
  },
  disconnectButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#FFFFFF',
  },
  urlButton: {
    backgroundColor: uiColors.surface,
    padding: 12,
    borderRadius: 8,
    marginBottom: 12,
  },
  urlLabel: {
    fontSize: 12,
    color: uiColors.textSecondary,
    marginBottom: 4,
  },
  urlText: {
    fontSize: 14,
    color: uiColors.accent,
    fontWeight: '500',
  },
  helpText: {
    fontSize: 12,
    color: uiColors.textSecondary,
    textAlign: 'center',
  },
});
