// API helper — all HTTP calls go through here
const api = {
  baseUrl: '',

  getToken() {
    return localStorage.getItem('token');
  },

  headers(withAuth = true) {
    const h = { 'Content-Type': 'application/json' };
    if (withAuth) {
      const token = this.getToken();
      if (token) h['Authorization'] = 'Bearer ' + token;
    }
    return h;
  },

  async request(method, path, body, withAuth = true) {
    const opts = {
      method,
      headers: this.headers(withAuth),
    };
    if (body) opts.body = JSON.stringify(body);

    console.log(`[API] ${method} ${path}`, body || '');
    const start = Date.now();

    const res = await fetch(this.baseUrl + path, opts);
    const duration = Date.now() - start;

    if (res.status === 401) {
      console.warn(`[API] 401 Unauthorized on ${path} (${duration}ms)`);
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.reload();
      throw new Error('Unauthorized');
    }

    const data = await res.json();
    if (!res.ok) {
      console.error(`[API] ${res.status} ${path} (${duration}ms)`, data);
      throw new Error(data.error || 'Request failed');
    }

    console.log(`[API] ${res.status} ${path} (${duration}ms)`, Array.isArray(data) ? `[${data.length} items]` : 'ok');
    return data;
  },

  // Auth
  register(username, email, password) {
    return this.request('POST', '/api/auth/register', { username, email, password }, false);
  },
  login(username, password) {
    return this.request('POST', '/api/auth/login', { username, password }, false);
  },

  // Messages (HTTP — used for initial history load)
  getConversation(userId) {
    return this.request('GET', '/api/messages/conversation/' + userId);
  },
  getConversations() {
    return this.request('GET', '/api/messages/conversations');
  },

  // Users
  searchUsers(q) {
    return this.request('GET', '/api/users/search?q=' + encodeURIComponent(q));
  },
};

// ── WebSocket (STOMP) connection manager ──
const ws = {
  client: null,
  onMessage: null, // callback set by chat.js

  connect(token) {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${protocol}//${location.host}/ws`;

    this.client = new StompJs.Client({
      brokerURL,
      connectHeaders: { Authorization: 'Bearer ' + token },
      reconnectDelay: 3000,
      onConnect: () => {
        console.log('[WS] STOMP connected');
        this.client.subscribe('/user/queue/messages', (frame) => {
          const msg = JSON.parse(frame.body);
          console.log('[WS] Message received:', msg.id);
          if (this.onMessage) this.onMessage(msg);
        });
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP error', frame.headers['message']);
      },
      onWebSocketClose: () => {
        console.warn('[WS] WebSocket closed');
      },
    });
    this.client.activate();
  },

  sendMessage(receiverId, content) {
    if (!this.client || !this.client.connected) {
      console.error('[WS] Not connected');
      return;
    }
    this.client.publish({
      destination: '/app/chat.send',
      body: JSON.stringify({ receiverId, content }),
    });
  },

  disconnect() {
    if (this.client) {
      this.client.deactivate();
      this.client = null;
      console.log('[WS] Disconnected');
    }
  },
};
