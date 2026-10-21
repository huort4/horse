// Horus Terminal WebSocket Relay - Secure Version
// Uses Durable Objects for state management

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // WebSocket upgrade
    if (request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const sessionId = url.searchParams.get("id");
      const token = url.searchParams.get("token");
      
      // Validate token
      if (!token || token !== env.AUTH_TOKEN) {
        return new Response("Unauthorized", { status: 401 });
      }
      
      // Validate session ID (must be random string)
      if (!sessionId || sessionId.length < 16) {
        return new Response("Invalid session ID", { status: 400 });
      }
      
      // Get Durable Object for this session
      const id = env.RELAY.idFromName(sessionId);
      const stub = env.RELAY.get(id);
      
      // Forward to Durable Object
      return stub.fetch(request);
    }
    
    // HTTP endpoints
    if (url.pathname === "/") {
      return new Response(getLandingPage(), {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }
    
    if (url.pathname === "/status") {
      return new Response(JSON.stringify({ status: "ok" }), {
        headers: { "Content-Type": "application/json" }
      });
    }
    
    return new Response("Not Found", { status: 404 });
  }
};

// Durable Object for each session
export class RelayDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.host = null;
    this.clients = new Set();
  }
  
  async fetch(request) {
    const url = new URL(request.url);
    
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 400 });
    }
    
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    
    server.accept();
    
    if (url.pathname === "/host") {
      this.handleHost(server);
    } else if (url.pathname === "/client") {
      this.handleClient(server);
    } else {
      server.close(1008, "Invalid path");
      return new Response(null, { status: 404 });
    }
    
    return new Response(null, { status: 101, webSocket: client });
  }
  
  handleHost(ws) {
    // Close existing host if any
    if (this.host && this.host.readyState === WebSocket.OPEN) {
      this.host.close(1000, "New host connected");
    }
    
    this.host = ws;
    
    // Notify clients
    this.broadcastToClients({ type: "host_connected" });
    
    ws.addEventListener("message", (event) => {
      this.broadcastToClients(event.data);
    });
    
    ws.addEventListener("close", () => {
      this.host = null;
      this.broadcastToClients({ type: "host_disconnected" });
    });
    
    ws.addEventListener("error", () => {
      this.host = null;
    });
  }
  
  handleClient(ws) {
    if (!this.host || this.host.readyState !== WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "error", message: "Host not connected" }));
    }
    
    this.clients.add(ws);
    
    ws.addEventListener("message", (event) => {
      if (this.host && this.host.readyState === WebSocket.OPEN) {
        this.host.send(event.data);
      }
    });
    
    ws.addEventListener("close", () => {
      this.clients.delete(ws);
    });
    
    ws.addEventListener("error", () => {
      this.clients.delete(ws);
    });
  }
  
  broadcastToClients(data) {
    const message = typeof data === "string" ? data : JSON.stringify(data);
    this.clients.forEach(client => {
      if (client.readyState === WebSocket.OPEN) {
        client.send(message);
      }
    });
  }
}

function getLandingPage() {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Horus Relay (Secure)</title>
  <style>
    body { font-family: system-ui; max-width: 800px; margin: 50px auto; padding: 20px; background: #0a0a0a; color: #e0e0e0; }
    h1 { color: #00ff9f; }
    code { background: #1a1a1a; padding: 2px 8px; border-radius: 4px; }
    pre { background: #1a1a1a; padding: 16px; border-radius: 8px; overflow-x: auto; }
    .warning { background: #2a1515; border-left: 3px solid #ff4444; padding: 12px; margin: 20px 0; }
    .step { margin: 20px 0; padding: 16px; background: #151515; border-left: 3px solid #00ff9f; }
  </style>
</head>
<body>
  <h1>🔐 Horus Terminal Relay (Secure)</h1>
  
  <div class="warning">
    <strong>⚠️ Security:</strong> Requires AUTH_TOKEN secret and random session ID (16+ chars)
  </div>
  
  <div class="step">
    <h3>Step 1: Generate Session ID</h3>
    <pre><code>SESSION_ID=$(openssl rand -hex 16)</code></pre>
  </div>
  
  <div class="step">
    <h3>Step 2: Phone (Host)</h3>
    <pre><code>wss://YOUR-WORKER.workers.dev/host?id=SESSION_ID&token=YOUR_TOKEN</code></pre>
  </div>
  
  <div class="step">
    <h3>Step 3: Browser (Client)</h3>
    <pre><code>wss://YOUR-WORKER.workers.dev/client?id=SESSION_ID&token=YOUR_TOKEN</code></pre>
  </div>
  
  <h2>Protocol</h2>
  <pre><code>{"type": "command", "data": "ls -la"}
{"type": "output", "data": "..."}</code></pre>
</body>
</html>`;
}
