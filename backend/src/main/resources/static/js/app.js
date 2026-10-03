// Main App — manages auth state and routing (vanilla JS, no React)
(function () {
  var root = document.getElementById('root');
  var user = null;
  var page = 'login'; // 'login' | 'register'

  function handleAuth(data) {
    localStorage.setItem('token', data.token);
    user = { id: data.userId, username: data.username };
    localStorage.setItem('user', JSON.stringify(user));
    console.log('[App] Logged in as', user.username);
    render();
  }

  function handleLogout() {
    console.log('[App] Logging out', user ? user.username : '');
    ws.disconnect();
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    user = null;
    page = 'login';
    render();
  }

  function render() {
    if (!user) {
      if (page === 'register') {
        renderRegisterPage(root, handleAuth, function () { page = 'login'; render(); });
      } else {
        renderLoginPage(root, handleAuth, function () { page = 'register'; render(); });
      }
      return;
    }
    renderChatPage(root, user, handleLogout);
  }

  // Restore session from localStorage
  var token = localStorage.getItem('token');
  var userData = localStorage.getItem('user');
  if (token && userData) {
    try {
      user = JSON.parse(userData);
      console.log('[App] Restored session for', user.username);
    } catch (e) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
    }
  }

  render();
})();
