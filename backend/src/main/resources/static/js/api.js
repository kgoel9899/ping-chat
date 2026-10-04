// API helper — all HTTP calls go through here
const api = {
  baseUrl: '',

  getToken() {
    return localStorage.getItem('token');
  },

  headers(withAuth) {
    if (withAuth === undefined) withAuth = true;
    const h = { 'Content-Type': 'application/json' };
    if (withAuth) {
      const token = this.getToken();
      if (token) h['Authorization'] = 'Bearer ' + token;
    }
    return h;
  },

  async request(method, path, body, withAuth) {
    if (withAuth === undefined) withAuth = true;
    const opts = {
      method: method,
      headers: this.headers(withAuth),
    };
    if (body) opts.body = JSON.stringify(body);

    console.log('[API] ' + method + ' ' + path, body || '');
    var start = Date.now();

    var res = await fetch(this.baseUrl + path, opts);
    var duration = Date.now() - start;

    if (res.status === 401) {
      console.warn('[API] 401 Unauthorized on ' + path + ' (' + duration + 'ms)');
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.reload();
      throw new Error('Unauthorized');
    }

    var data = await res.json();
    if (!res.ok) {
      console.error('[API] ' + res.status + ' ' + path + ' (' + duration + 'ms)', data);
      throw new Error(data.error || 'Request failed');
    }

    console.log('[API] ' + res.status + ' ' + path + ' (' + duration + 'ms)',
      Array.isArray(data) ? '[' + data.length + ' items]' : 'ok');
    return data;
  },

  // Auth
  register(username, email, password) {
    return this.request('POST', '/api/auth/register', { username: username, email: email, password: password }, false);
  },
  login(username, password) {
    return this.request('POST', '/api/auth/login', { username: username, password: password }, false);
  },

  // Messages (HTTP — used for initial history load + pagination)
  getConversation(userId, page) {
    if (page === undefined) page = 0;
    return this.request('GET', '/api/messages/conversation/' + userId + '?page=' + page);
  },
  getConversations(page) {
    if (page === undefined) page = 0;
    return this.request('GET', '/api/messages/conversations?page=' + page);
  },

  // Users
  searchUsers(q) {
    return this.request('GET', '/api/users/search?q=' + encodeURIComponent(q));
  },

  // Images — presigned S3 URLs via image-service
  getImageUploadUrl(filename, contentType) {
    return this.request('POST', '/api/images/presign/upload', { filename: filename, contentType: contentType });
  },
  refreshImageDownloadUrl(imageKey) {
    return this.request('GET', '/api/images/presign/download?imageKey=' + encodeURIComponent(imageKey));
  },
  async uploadImageToS3(uploadUrl, file) {
    var res = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!res.ok) throw new Error('S3 upload failed: ' + res.status);
  },
};

// ── WebSocket (STOMP) connection manager ──
var ws = {
  client: null,
  onMessage: null, // callback set by chat.js

  connect(token) {
    var self = this;
    var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var brokerURL = protocol + '//' + location.host + '/ws';

    this.client = new StompJs.Client({
      brokerURL: brokerURL,
      connectHeaders: { Authorization: 'Bearer ' + token },
      reconnectDelay: 3000,
      onConnect: function () {
        console.log('[WS] STOMP connected');
        self.client.subscribe('/user/queue/messages', function (frame) {
          var msg = JSON.parse(frame.body);
          console.log('[WS] Message received:', msg.id);
          if (self.onMessage) self.onMessage(msg);
        });
      },
      onStompError: function (frame) {
        console.error('[WS] STOMP error', frame.headers['message']);
      },
      onWebSocketClose: function () {
        console.warn('[WS] WebSocket closed');
      },
    });
    this.client.activate();
  },

  sendMessage(receiverId, content, imageUrl, imageKey) {
    if (!this.client || !this.client.connected) {
      console.error('[WS] Not connected');
      return null;
    }
    var clientId = crypto.randomUUID();
    var payload = { receiverId: receiverId, content: content, clientId: clientId };
    if (imageUrl) payload.imageUrl = imageUrl;
    if (imageKey) payload.imageKey = imageKey;
    this.client.publish({
      destination: '/app/chat.send',
      body: JSON.stringify(payload),
    });
    return clientId;
  },

  disconnect() {
    if (this.client) {
      this.client.deactivate();
      this.client = null;
      console.log('[WS] Disconnected');
    }
  },
};
