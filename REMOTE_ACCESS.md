# Horus Remote Access

## WebSocket-based Terminal Access

Horus now supports remote terminal access without USB/ADB. Connect from anywhere using WebSocket.

### How It Works

1. **WebSocket Server**: The app runs a WebSocket client that connects to a relay server
2. **Tunnel Service**: Use Cloudflare Tunnel or ngrok to expose your device
3. **Web Client**: Access your terminal from any web browser

### Setup

#### 1. Choose a Tunnel Service

**Cloudflare Tunnel** (recommended)
- Free
- No account required
- URL: `wss://trycloudflare.com`

**ngrok**
- Requires account
- More stable
- URL: `wss://tunnel.ngrok.io`

#### 2. Connect

1. Open Horus app
2. Go to Settings → Remote Access
3. Select tunnel service
4. Tap "Connect"
5. Copy the connection URL

#### 3. Access Terminal

Open the connection URL in a web browser to access your terminal.

### Architecture

```
┌──────────┐      WebSocket       ┌──────────┐      WebSocket      ┌──────────┐
│  Phone   │◄──────────────────►│  Relay   │◄─────────────────►│  Browser │
│  Horus   │                     │  Server  │                    │  Client  │
└──────────┘                     └──────────┘                    └──────────┘
```

### Development

#### Build WebSocket Relay Server

```bash
# Simple WebSocket relay
npm install ws express
node relay-server.js
```

#### relay-server.js

```javascript
const WebSocket = require('ws');
const express = require('express');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const devices = new Map();
const clients = new Map();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const deviceId = url.searchParams.get('device');
  const type = url.searchParams.get('type');

  if (type === 'host') {
    // Device connection
    devices.set(deviceId, ws);
    console.log(`Device connected: ${deviceId}`);
    
    ws.on('message', (data) => {
      // Forward to connected client
      const client = clients.get(deviceId);
      if (client && client.readyState === WebSocket.OPEN) {
        client.send(data);
      }
    });
  } else {
    // Browser client connection
    clients.set(deviceId, ws);
    console.log(`Client connected to device: ${deviceId}`);
    
    ws.on('message', (data) => {
      // Forward to device
      const device = devices.get(deviceId);
      if (device && device.readyState === WebSocket.OPEN) {
        device.send(data);
      }
    });
  }

  ws.on('close', () => {
    devices.delete(deviceId);
    clients.delete(deviceId);
  });
});

app.get('/', (req, res) => {
  res.send('Horus WebSocket Relay');
});

server.listen(8080, () => {
  console.log('Relay server running on port 8080');
});
```

### Security

- All connections are encrypted (wss://)
- Device ID is unique and random
- Connection is closed when app goes to background
- Optional: Add password protection

### Limitations

- Requires internet connection
- Latency depends on network quality
- May be blocked by firewalls

### Troubleshooting

**Connection Failed**
- Check internet connection
- Try different tunnel service
- Check firewall settings

**Slow Response**
- Check network speed
- Use a closer relay server
- Reduce terminal output size

### Future Improvements

- [ ] Password protection
- [ ] End-to-end encryption
- [ ] Multiple session support
- [ ] File transfer
- [ ] Custom relay server
