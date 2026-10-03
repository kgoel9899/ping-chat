// Chat page — real-time via WebSocket (STOMP), no polling
function ChatPage({ user, onLogout }) {
  const [conversations, setConversations] = React.useState([]);
  const [selectedUser, setSelectedUser] = React.useState(null);
  const [messages, setMessages] = React.useState([]);
  const [newMessage, setNewMessage] = React.useState('');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [searchResults, setSearchResults] = React.useState([]);
  const messagesEndRef = React.useRef(null);
  const messagesContainerRef = React.useRef(null);
  const selectedUserRef = React.useRef(null);

  // Pagination state
  const [msgPage, setMsgPage] = React.useState(0);
  const [convPage, setConvPage] = React.useState(0);
  const [hasMoreMsgs, setHasMoreMsgs] = React.useState(true);
  const [hasMoreConvs, setHasMoreConvs] = React.useState(true);
  const [loadingMsgs, setLoadingMsgs] = React.useState(false);
  const [loadingConvs, setLoadingConvs] = React.useState(false);

  // Keep ref in sync so WS callback sees latest selectedUser
  React.useEffect(() => {
    selectedUserRef.current = selectedUser;
  }, [selectedUser]);

  // ─── Load conversation list (page 0 on mount, append on scroll) ───
  const loadConversations = React.useCallback(async (page = 0) => {
    if (loadingConvs) return;
    setLoadingConvs(true);
    try {
      const data = await api.getConversations(page);
      if (page === 0) {
        setConversations(data);
      } else {
        setConversations((prev) => {
          const ids = new Set(prev.map((c) => c.id));
          return [...prev, ...data.filter((c) => !ids.has(c.id))];
        });
      }
      setHasMoreConvs(data.length === 15);
      setConvPage(page);
    } catch (err) {
      console.error('[Chat] Failed to load conversations', err);
    } finally {
      setLoadingConvs(false);
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
            // If server echoed the client-generated UUID, use it for exact dedup.
            // Fall back to composite key for DB-loaded messages (clientId is null there).
            const isDup = msg.clientId
              ? prev.some((m) => m.clientId === msg.clientId)
              : prev.some((m) => m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp);
            if (isDup) return prev;
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
  const loadMessages = React.useCallback(async (page = 0) => {
    if (!selectedUser || loadingMsgs) return;
    setLoadingMsgs(true);
    try {
      const data = await api.getConversation(selectedUser.id, page);
      if (page === 0) {
        setMessages(data);
        setMsgPage(0);
        setHasMoreMsgs(data.length === 15);
      } else {
        // Prepend older messages, preserve scroll position
        const container = messagesContainerRef.current;
        const prevHeight = container ? container.scrollHeight : 0;
        setMessages((prev) => {
          const ids = new Set(prev.map((m) => m.id));
          const older = data.filter((m) => !ids.has(m.id));
          return [...older, ...prev];
        });
        setMsgPage(page);
        setHasMoreMsgs(data.length === 15);
        // Restore scroll so the view doesn't jump
        requestAnimationFrame(() => {
          if (container) {
            container.scrollTop = container.scrollHeight - prevHeight;
          }
        });
      }
    } catch (err) {
      console.error('[Chat] Failed to load messages', err);
    } finally {
      setLoadingMsgs(false);
    }
  }, [selectedUser, loadingMsgs]);

  React.useEffect(() => {
    if (selectedUser) {
      setMessages([]);
      setMsgPage(0);
      setHasMoreMsgs(true);
      loadMessages(0);
    }
  }, [selectedUser]);

  // ─── Auto-scroll to newest message (only on page 0 / new messages) ───
  const shouldAutoScroll = React.useRef(true);
  React.useEffect(() => {
    if (shouldAutoScroll.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  // ─── Scroll up to load older messages ───
  const handleMessagesScroll = (e) => {
    const el = e.target;
    shouldAutoScroll.current = el.scrollHeight - el.scrollTop - el.clientHeight < 50;
    if (el.scrollTop === 0 && hasMoreMsgs && !loadingMsgs) {
      loadMessages(msgPage + 1);
    }
  };

  // ─── Scroll down to load more conversations ───
  const handleConversationsScroll = (e) => {
    const el = e.target;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 50 && hasMoreConvs && !loadingConvs) {
      loadConversations(convPage + 1);
    }
  };

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
          <div className="conversation-list" onScroll={handleConversationsScroll}>
            {conversations.length === 0 ? (
              <div style={{ padding: '20px', textAlign: 'center', color: '#8696a0', fontSize: '14px' }}>
                No conversations yet.<br />Search for a user to start chatting.
              </div>
            ) : (
              React.createElement(React.Fragment, null,
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
                )),
                loadingConvs && <div style={{ textAlign: 'center', padding: '10px', color: '#8696a0' }}>Loading...</div>
              )
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
            <div className="messages" ref={messagesContainerRef} onScroll={handleMessagesScroll}>
              {loadingMsgs && <div style={{ textAlign: 'center', padding: '10px', color: '#8696a0' }}>Loading older messages...</div>}
              {messages.map((msg) => (
                <div
                  key={msg.clientId ?? (msg.id + '_' + msg.timestamp)}
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
