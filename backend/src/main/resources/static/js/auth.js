// Auth pages — vanilla JS (no React)

function renderLoginPage(root, onLogin, onSwitch) {
  root.innerHTML =
    '<div class="auth-container">' +
      '<h2>ChatApp</h2>' +
      '<div id="auth-error" class="error-msg" style="display:none"></div>' +
      '<form id="login-form">' +
        '<input type="text" id="login-username" placeholder="Username" required />' +
        '<input type="password" id="login-password" placeholder="Password" required />' +
        '<button type="submit">Login</button>' +
      '</form>' +
      '<p>Don\'t have an account? <a id="switch-register" href="#">Register</a></p>' +
    '</div>';

  document.getElementById('login-form').addEventListener('submit', async function (e) {
    e.preventDefault();
    var errEl = document.getElementById('auth-error');
    errEl.style.display = 'none';
    var username = document.getElementById('login-username').value;
    var password = document.getElementById('login-password').value;
    try {
      var data = await api.login(username, password);
      onLogin(data);
    } catch (err) {
      errEl.textContent = err.message || 'Login failed';
      errEl.style.display = 'block';
    }
  });

  document.getElementById('switch-register').addEventListener('click', function (e) {
    e.preventDefault();
    onSwitch();
  });
}

function renderRegisterPage(root, onRegister, onSwitch) {
  root.innerHTML =
    '<div class="auth-container">' +
      '<h2>ChatApp</h2>' +
      '<div id="auth-error" class="error-msg" style="display:none"></div>' +
      '<form id="register-form">' +
        '<input type="text" id="reg-username" placeholder="Username" required />' +
        '<input type="email" id="reg-email" placeholder="Email" required />' +
        '<input type="password" id="reg-password" placeholder="Password (min 6 chars)" required minlength="6" />' +
        '<button type="submit">Register</button>' +
      '</form>' +
      '<p>Already have an account? <a id="switch-login" href="#">Login</a></p>' +
    '</div>';

  document.getElementById('register-form').addEventListener('submit', async function (e) {
    e.preventDefault();
    var errEl = document.getElementById('auth-error');
    errEl.style.display = 'none';
    var username = document.getElementById('reg-username').value;
    var email = document.getElementById('reg-email').value;
    var password = document.getElementById('reg-password').value;
    try {
      var data = await api.register(username, email, password);
      onRegister(data);
    } catch (err) {
      errEl.textContent = err.message || 'Registration failed';
      errEl.style.display = 'block';
    }
  });

  document.getElementById('switch-login').addEventListener('click', function (e) {
    e.preventDefault();
    onSwitch();
  });
}
