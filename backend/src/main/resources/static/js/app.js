// Main App — manages auth state and routing
function App() {
  const [user, setUser] = React.useState(null);
  const [page, setPage] = React.useState('login'); // 'login' | 'register'

  // Restore session from localStorage
  React.useEffect(() => {
    const token = localStorage.getItem('token');
    const userData = localStorage.getItem('user');
    if (token && userData) {
      try {
        setUser(JSON.parse(userData));
        console.log('[App] Restored session for', JSON.parse(userData).username);
      } catch (e) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
      }
    }
  }, []);

  const handleAuth = (data) => {
    localStorage.setItem('token', data.token);
    const u = { id: data.userId, username: data.username };
    localStorage.setItem('user', JSON.stringify(u));
    setUser(u);
    console.log('[App] Logged in as', u.username);
  };

  const handleLogout = () => {
    console.log('[App] Logging out', user?.username);
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
    setPage('login');
  };

  if (!user) {
    if (page === 'register') {
      return <RegisterPage onRegister={handleAuth} onSwitch={() => setPage('login')} />;
    }
    return <LoginPage onLogin={handleAuth} onSwitch={() => setPage('register')} />;
  }

  return <ChatPage user={user} onLogout={handleLogout} />;
}

// Mount
const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App />);
