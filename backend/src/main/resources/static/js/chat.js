// Chat page — vanilla JS, real-time via WebSocket (STOMP), no polling, no React

var chatState = {
  user: null,
  onLogout: null,
  conversations: [],
  selectedUser: null,
  messages: [],
  searchQuery: '',
  searchResults: [],
  msgPage: 0,
  convPage: 0,
  hasMoreMsgs: true,
  hasMoreConvs: true,
  loadingMsgs: false,
  loadingConvs: false,
  shouldAutoScroll: true,
};

function formatTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// ─── Render helpers ───

function renderConversationList() {
  var el = document.getElementById('conversation-list');
  if (!el) return;

  if (chatState.searchResults.length > 0) {
    el.className = 'search-results';
    el.innerHTML = chatState.searchResults.map(function (u) {
      return '<div class="search-result-item" data-uid="' + u.id + '" data-uname="' + u.username + '">' +
        '<div class="avatar">' + u.username[0].toUpperCase() + '</div>' +
        '<span>' + u.username + '</span>' +
      '</div>';
    }).join('');
    el.querySelectorAll('.search-result-item').forEach(function (item) {
      item.addEventListener('click', function () {
        selectUser({ id: parseInt(item.dataset.uid), username: item.dataset.uname });
      });
    });
    return;
  }

  el.className = 'conversation-list';

  if (chatState.conversations.length === 0) {
    el.innerHTML = '<div style="padding:20px;text-align:center;color:#8696a0;font-size:14px">' +
      'No conversations yet.<br>Search for a user to start chatting.</div>';
    return;
  }

  var html = chatState.conversations.map(function (c) {
    var active = chatState.selectedUser && chatState.selectedUser.id === c.id ? ' active' : '';
    return '<div class="conversation-item' + active + '" data-uid="' + c.id + '" data-uname="' + c.username + '">' +
      '<div class="avatar">' + c.username[0].toUpperCase() + '</div>' +
      '<div class="conv-info"><span>' + c.username + '</span></div>' +
    '</div>';
  }).join('');

  if (chatState.loadingConvs) {
    html += '<div style="text-align:center;padding:10px;color:#8696a0">Loading...</div>';
  }

  el.innerHTML = html;

  el.querySelectorAll('.conversation-item').forEach(function (item) {
    item.addEventListener('click', function () {
      selectUser({ id: parseInt(item.dataset.uid), username: item.dataset.uname });
    });
  });
}

function escapeHtml(text) {
  var div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function renderMessages() {
  var el = document.getElementById('messages-container');
  if (!el) return;

  var html = '';
  if (chatState.loadingMsgs) {
    html += '<div style="text-align:center;padding:10px;color:#8696a0">Loading older messages...</div>';
  }

  html += chatState.messages.map(function (msg) {
    var cls = msg.senderId === chatState.user.id ? 'sent' : 'received';
    return '<div class="message ' + cls + '">' +
      '<div>' + escapeHtml(msg.content) + '</div>' +
      '<div class="time">' + formatTime(msg.timestamp) + '</div>' +
    '</div>';
  }).join('');

  html += '<div id="messages-end"></div>';
  el.innerHTML = html;

  if (chatState.shouldAutoScroll) {
    var end = document.getElementById('messages-end');
    if (end) end.scrollIntoView({ behavior: 'smooth' });
  }
}

function renderChatArea() {
  var el = document.getElementById('chat-area');
  if (!el) return;

  if (!chatState.selectedUser) {
    el.innerHTML = '<div class="no-chat">Select a conversation or search for a user to start chatting</div>';
    return;
  }

  el.innerHTML =
    '<div class="chat-header">' +
      '<div class="avatar">' + chatState.selectedUser.username[0].toUpperCase() + '</div>' +
      '<h3>' + escapeHtml(chatState.selectedUser.username) + '</h3>' +
    '</div>' +
    '<div class="messages" id="messages-container"></div>' +
    '<form class="message-input" id="send-form">' +
      '<input type="text" id="msg-input" placeholder="Type a message..." />' +
      '<button type="submit">Send</button>' +
    '</form>';

  renderMessages();

  // Scroll handler for loading older messages
  var msgContainer = document.getElementById('messages-container');
  msgContainer.addEventListener('scroll', function () {
    chatState.shouldAutoScroll = msgContainer.scrollHeight - msgContainer.scrollTop - msgContainer.clientHeight < 50;
    if (msgContainer.scrollTop === 0 && chatState.hasMoreMsgs && !chatState.loadingMsgs) {
      loadMessages(chatState.msgPage + 1);
    }
  });

  // Send message form
  document.getElementById('send-form').addEventListener('submit', function (e) {
    e.preventDefault();
    var input = document.getElementById('msg-input');
    var text = input.value.trim();
    if (!text || !chatState.selectedUser) return;
    ws.sendMessage(chatState.selectedUser.id, text);
    input.value = '';
  });
}

// ─── Data operations ───

async function loadConversations(page) {
  if (page === undefined) page = 0;
  if (chatState.loadingConvs) return;
  chatState.loadingConvs = true;
  try {
    var data = await api.getConversations(page);
    if (page === 0) {
      chatState.conversations = data;
    } else {
      var ids = {};
      chatState.conversations.forEach(function (c) { ids[c.id] = true; });
      data.forEach(function (c) {
        if (!ids[c.id]) chatState.conversations.push(c);
      });
    }
    chatState.hasMoreConvs = data.length === 15;
    chatState.convPage = page;
  } catch (err) {
    console.error('[Chat] Failed to load conversations', err);
  } finally {
    chatState.loadingConvs = false;
    renderConversationList();
  }
}

async function loadMessages(page) {
  if (page === undefined) page = 0;
  if (!chatState.selectedUser || chatState.loadingMsgs) return;
  chatState.loadingMsgs = true;
  renderMessages();
  try {
    var data = await api.getConversation(chatState.selectedUser.id, page);
    if (page === 0) {
      chatState.messages = data;
      chatState.msgPage = 0;
      chatState.hasMoreMsgs = data.length === 15;
    } else {
      // Prepend older messages, preserve scroll position
      var container = document.getElementById('messages-container');
      var prevHeight = container ? container.scrollHeight : 0;
      var existingIds = {};
      chatState.messages.forEach(function (m) { existingIds[m.id] = true; });
      var older = data.filter(function (m) { return !existingIds[m.id]; });
      chatState.messages = older.concat(chatState.messages);
      chatState.msgPage = page;
      chatState.hasMoreMsgs = data.length === 15;
      // Render then restore scroll position
      chatState.loadingMsgs = false;
      renderMessages();
      requestAnimationFrame(function () {
        if (container) {
          container.scrollTop = container.scrollHeight - prevHeight;
        }
      });
      return;
    }
  } catch (err) {
    console.error('[Chat] Failed to load messages', err);
  } finally {
    chatState.loadingMsgs = false;
    renderMessages();
  }
}

function selectUser(u) {
  chatState.selectedUser = u;
  chatState.searchQuery = '';
  chatState.searchResults = [];
  chatState.messages = [];
  chatState.msgPage = 0;
  chatState.hasMoreMsgs = true;
  chatState.shouldAutoScroll = true;
  var searchInput = document.getElementById('search-input');
  if (searchInput) searchInput.value = '';
  renderConversationList();
  renderChatArea();
  loadMessages(0);
}

async function handleSearch(query) {
  chatState.searchQuery = query;
  if (query.length < 2) {
    chatState.searchResults = [];
    renderConversationList();
    return;
  }
  try {
    var data = await api.searchUsers(query);
    chatState.searchResults = data.filter(function (u) { return u.id !== chatState.user.id; });
  } catch (err) {
    console.error('[Chat] Search failed', err);
  }
  renderConversationList();
}

// ─── Main chat page render ───

function renderChatPage(root, user, onLogout) {
  chatState.user = user;
  chatState.onLogout = onLogout;
  chatState.conversations = [];
  chatState.selectedUser = null;
  chatState.messages = [];
  chatState.searchQuery = '';
  chatState.searchResults = [];
  chatState.msgPage = 0;
  chatState.convPage = 0;
  chatState.hasMoreMsgs = true;
  chatState.hasMoreConvs = true;

  root.innerHTML =
    '<div class="chat-container">' +
      '<div class="sidebar">' +
        '<div class="sidebar-header">' +
          '<h3>' + escapeHtml(user.username) + '</h3>' +
          '<button id="logout-btn">Logout</button>' +
        '</div>' +
        '<div class="search-box">' +
          '<input type="text" id="search-input" placeholder="Search users..." />' +
        '</div>' +
        '<div class="conversation-list" id="conversation-list"></div>' +
      '</div>' +
      '<div class="chat-area" id="chat-area">' +
        '<div class="no-chat">Select a conversation or search for a user to start chatting</div>' +
      '</div>' +
    '</div>';

  document.getElementById('logout-btn').addEventListener('click', onLogout);

  document.getElementById('search-input').addEventListener('input', function (e) {
    handleSearch(e.target.value);
  });

  // Conversation list scroll for pagination
  document.getElementById('conversation-list').addEventListener('scroll', function (e) {
    var el = e.target;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 50 && chatState.hasMoreConvs && !chatState.loadingConvs) {
      loadConversations(chatState.convPage + 1);
    }
  });

  // Connect WebSocket
  ws.onMessage = function (msg) {
    // Update messages if this message belongs to the active conversation
    var sel = chatState.selectedUser;
    if (sel) {
      var isForConv =
        (msg.senderId === chatState.user.id && msg.receiverId === sel.id) ||
        (msg.senderId === sel.id && msg.receiverId === chatState.user.id);
      if (isForConv) {
        // Dedup: check by clientId first, then by composite key
        var isDup = msg.clientId
          ? chatState.messages.some(function (m) { return m.clientId === msg.clientId; })
          : chatState.messages.some(function (m) {
              return m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp;
            });
        if (!isDup) {
          chatState.messages.push(msg);
          renderMessages();
        }
      }
    }

    // Move conversation partner to top of list
    var partnerId = msg.senderId === chatState.user.id ? msg.receiverId : msg.senderId;
    var partnerName = msg.senderId === chatState.user.id ? msg.receiverUsername : msg.senderUsername;
    chatState.conversations = chatState.conversations.filter(function (c) { return c.id !== partnerId; });
    chatState.conversations.unshift({ id: partnerId, username: partnerName });
    renderConversationList();
  };

  ws.connect(api.getToken());

  // Load initial conversations
  loadConversations(0);
}
