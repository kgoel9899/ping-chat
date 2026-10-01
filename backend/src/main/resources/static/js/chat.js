// Chat page — real-time via WebSocket (STOMP), no polling
function ChatPage({ user, onLogout }) {
  const [conversations, setConversations] = React.useState([]);
  const [selectedUser, setSelectedUser] = React.useState(null);
  const [messages, setMessages] = React.useState([]);
  const [newMessage, setNewMessage] = React.useState('');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [searchResults, setSearchResults] = React.useState([]);
  const messagesEndRef = React.useRef(null);
  const selectedUserRef = React.useRef(null);

  // Keep ref in sync so WS callback sees latest selectedUser
  React.useEffect(() => {
    selectedUserRef.current = selectedUser;
  }, [selectedUser]);

  // ─── Load conversation list once on mount ───
  const loadConversations = React.useCallback(async () => {
    try {
      const data = await api.getConversations();
      setConversations(data);
    } catch (err) {
      console.error('[Chat] Failed to load conversations', err);
    }
  }, []);

  // ─── Connect WebSocket + subscribe ───
  React.useEffect(() => {
    loadConversations();

    ws.onMessage = (msg) => {
      // Update messages if this message belongs to the active conversation
      const sel = selectedUserRef.current;
      if (sel) {
        const isForConv =
          (msg.senderId === user.id && msg.receiverId === sel.id) ||
          (msg.senderId === sel.id && msg.receiverId === user.id);
        if (isForConv) {
          setMessages((prev) => {
            if (prev.some((m) => m.id === msg.id)) return prev; // dedup
            return [...prev, msg];
          });
        }
      }

      // Move conversation partner to top of list
      const partnerId = msg.senderId === user.id ? msg.receiverId : msg.senderId;
      const partnerName = msg.senderId === user.id ? msg.receiverUsername : msg.senderUsername;
      setConversations((prev) => {
        const filtered = prev.filter((c) => c.id !== partnerId);
        return [{ id: partnerId, username: partnerName }, ...filtered];
      });
    };

    ws.connect(api.getToken());
    return () => ws.disconnect();
  }, [user.id, loadConversations]);

  // ─── Load messages when selecting a conversation ───
  const loadMessages = React.useCallback(async () => {
    if (!selectedUser) return;
    try {
      const data = await api.getConversation(selectedUser.id);
      setMessages(data);
    } catch (err) {
      console.error('[Chat] Failed to load messages', err);
    }
  }, [selectedUser]);

  React.useEffect(() => {
    if (selectedUser) loadMessages();
  }, [selectedUser, loadMessages]);

  // ─── Auto-scroll to newest message ───
  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // ─── Send message via WebSocket ───
  const sendMessage = (e) => {
    e.preventDefault();
    if (!newMessage.trim() || !selectedUser) return;
    ws.sendMessage(selectedUser.id, newMessage);
    setNewMessage('');
  };

  // ─── Search users ───
  const handleSearch = async (query) => {
    setSearchQuery(query);
    if (query.length < 2) {
      setSearchResults([]);
      return;
    }
    try {
      const data = await api.searchUsers(query);
      setSearchResults(data.filter((u) => u.id !== user.id));
    } catch (err) {
      console.error('[Chat] Search failed', err);
    }
  };

  const selectUser = (u) => {
    setSelectedUser(u);
    setSearchQuery('');
    setSearchResults([]);
  };

  const formatTime = (ts) =>
    new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="chat-container">
      {/* ── Sidebar ── */}
      <div className="sidebar">
        <div className="sidebar-header">
          <h3>{user.username}</h3>
          <button onClick={onLogout}>Logout</button>
        </div>
        <div className="search-box">
          <input
            type="text"
            placeholder="Search users..."
            value={searchQuery}
            onChange={(e) => handleSearch(e.target.value)}
          />
        </div>

        {searchResults.length > 0 ? (
          <div className="search-results">
            {searchResults.map((u) => (
              <div key={u.id} className="search-result-item" onClick={() => selectUser(u)}>
                <div className="avatar">{u.username[0].toUpperCase()}</div>
                <span>{u.username}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="conversation-list">
            {conversations.length === 0 ? (
              <div style={{ padding: '20px', textAlign: 'center', color: '#8696a0', fontSize: '14px' }}>
                No conversations yet.<br />Search for a user to start chatting.
              </div>
            ) : (
              conversations.map((c) => (
                <div
                  key={c.id}
                  className={'conversation-item' + (selectedUser?.id === c.id ? ' active' : '')}
                  onClick={() => setSelectedUser(c)}
                >
                  <div className="avatar">{c.username[0].toUpperCase()}</div>
                  <div className="conv-info">
                    <span>{c.username}</span>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {/* ── Chat Area ── */}
      <div className="chat-area">
        {selectedUser ? (
          <React.Fragment>
            <div className="chat-header">
              <div className="avatar">{selectedUser.username[0].toUpperCase()}</div>
              <h3>{selectedUser.username}</h3>
            </div>
            <div className="messages">
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={'message ' + (msg.senderId === user.id ? 'sent' : 'received')}
                >
                  <div>{msg.content}</div>
                  <div className="time">{formatTime(msg.timestamp)}</div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>
            <form className="message-input" onSubmit={sendMessage}>
              <input
                type="text"
                placeholder="Type a message..."
                value={newMessage}
                onChange={(e) => setNewMessage(e.target.value)}
              />
              <button type="submit">Send</button>
            </form>
          </React.Fragment>
        ) : (
          <div className="no-chat">
            Select a conversation or search for a user to start chatting
          </div>
        )}
      </div>
    </div>
  );
}
