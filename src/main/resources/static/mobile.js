const loginForm = document.querySelector('#app-login-form');
const approveForm = document.querySelector('#approve-form');
const message = document.querySelector('#mobile-message');
const appSession = document.querySelector('#app-session');
const appUsername = document.querySelector('#app-username');
const ticket = new URLSearchParams(window.location.search).get('ticket');
const tokenKey = 'bluegate-mobile-token';
let csrf;

function appToken() {
  return localStorage.getItem(tokenKey);
}

function showAppState(username) {
  loginForm.hidden = Boolean(username);
  appSession.hidden = !username;
  approveForm.hidden = true;
  appUsername.textContent = username || '';
  if (username && !ticket) message.textContent = '模拟 APP 已登录。现在打开电脑登录页的二维码链接即可确认登录。';
  else if (!username) message.textContent = '请先登录模拟 APP。演示账户为 demo / changeit。';
}

async function loadCsrf() {
  const response = await fetch('/api/mobile/csrf');
  if (!response.ok) throw new Error('无法建立安全连接');
  csrf = await response.json();
}

async function scan() {
  if (!ticket || !appToken()) return;
  await loadCsrf();
  const response = await fetch(`/api/mobile/qr-sessions/${encodeURIComponent(ticket)}/scan`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${appToken()}`, [csrf.headerName]: csrf.token },
  });
  if (response.status === 401) {
    localStorage.removeItem(tokenKey);
    showAppState(null);
    throw new Error('模拟 APP 登录已过期，请重新登录');
  }
  if (!response.ok) throw new Error('二维码已过期，请返回电脑刷新后重试');
  approveForm.hidden = false;
  message.textContent = '请求已识别。请核对登录是否由你本人发起。';
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = loginForm.querySelector('button');
  button.disabled = true;
  message.classList.remove('is-error');
  message.textContent = '正在登录模拟 APP…';
  const data = new FormData(loginForm);
  try {
    await loadCsrf();
    const response = await fetch('/api/mobile/login', {
      method: 'POST',
      headers: {
        Authorization: `Basic ${btoa(`${data.get('username')}:${data.get('password')}`)}`,
        [csrf.headerName]: csrf.token,
      },
    });
    if (response.status === 401) throw new Error('账户或密码不正确');
    if (!response.ok) throw new Error('模拟 APP 登录失败，请重试');
    const result = await response.json();
    localStorage.setItem(tokenKey, result.token);
    loginForm.reset();
    showAppState(result.username);
    if (ticket) await scan();
  } catch (error) {
    message.classList.add('is-error');
    message.textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

approveForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = approveForm.querySelector('button');
  button.disabled = true;
  message.classList.remove('is-error');
  message.textContent = '正在确认登录请求…';
  try {
    await loadCsrf();
    const response = await fetch(`/api/mobile/qr-sessions/${encodeURIComponent(ticket)}/approve`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${appToken()}`, [csrf.headerName]: csrf.token },
    });
    if (response.status === 401) throw new Error('模拟 APP 登录已过期，请重新登录');
    if (response.status === 410) throw new Error('二维码已过期，请返回电脑重新发起登录');
    if (!response.ok) throw new Error('当前登录请求无法确认，请重新扫描二维码');
    approveForm.hidden = true;
    message.textContent = '已确认登录。请返回电脑继续使用。';
  } catch (error) {
    message.classList.add('is-error');
    message.textContent = error.message;
    button.disabled = false;
  }
});

document.querySelector('#app-logout').addEventListener('click', async () => {
  try {
    await loadCsrf();
    await fetch('/api/mobile/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${appToken()}`, [csrf.headerName]: csrf.token },
    });
  } finally {
    localStorage.removeItem(tokenKey);
    showAppState(null);
  }
});

async function initialize() {
  if (!appToken()) {
    showAppState(null);
    return;
  }
  try {
    const response = await fetch('/api/mobile/me', {
      headers: { Authorization: `Bearer ${appToken()}` },
    });
    if (!response.ok) throw new Error('模拟 APP 登录已过期');
    const user = await response.json();
    showAppState(user.username);
    await scan();
  } catch (error) {
    localStorage.removeItem(tokenKey);
    showAppState(null);
    message.classList.add('is-error');
    message.textContent = error.message;
  }
}

initialize();
