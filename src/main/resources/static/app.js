const statusText = document.querySelector('#status-text');
const statusRow = document.querySelector('.status-row');
const qrImage = document.querySelector('#qr-image');
const qrOverlay = document.querySelector('#qr-overlay');
const loginView = document.querySelector('#login-view');
const successView = document.querySelector('#success-view');
const steps = ['#step-scan', '#step-confirm', '#step-done'].map((selector) => document.querySelector(selector));

let csrf;
let ticket;
let pollTimer;
let polling = false;

async function readCsrf() {
  const response = await fetch('/api/csrf');
  if (!response.ok) throw new Error('无法建立安全连接');
  csrf = await response.json();
}

async function request(url, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.method && options.method !== 'GET') headers.set(csrf.headerName, csrf.token);
  return fetch(url, { ...options, headers, credentials: 'same-origin' });
}

function updateState(state) {
  statusRow.classList.toggle('is-scanned', state === 'SCANNED' || state === 'APPROVED');
  if (state === 'PENDING') statusText.textContent = '请使用手机扫描二维码';
  if (state === 'SCANNED') statusText.textContent = '已扫描，请在手机上确认登录';
  if (state === 'APPROVED') statusText.textContent = '身份已确认，正在安全登录…';
  if (state === 'EXPIRED') statusText.textContent = '二维码已过期，请刷新后重试';

  steps[0].classList.toggle('is-complete', state !== 'PENDING' && state !== 'EXPIRED');
  steps[1].classList.toggle('is-current', state === 'SCANNED');
  steps[1].classList.toggle('is-complete', state === 'APPROVED');
  steps[2].classList.toggle('is-current', state === 'APPROVED');
  qrOverlay.hidden = state !== 'EXPIRED';
  qrImage.style.visibility = state === 'EXPIRED' ? 'hidden' : 'visible';
}

async function showSession() {
  const response = await fetch('/api/me');
  if (!response.ok) throw new Error('登录状态读取失败');
  const user = await response.json();
  document.querySelector('#username').textContent = user.username;
  loginView.hidden = true;
  successView.hidden = false;
}

async function poll() {
  if (polling) return;
  polling = true;
  try {
    const response = await fetch(`/api/qr-sessions/${encodeURIComponent(ticket)}`);
    if (response.status === 404 || response.status === 410) {
      updateState('EXPIRED');
      clearInterval(pollTimer);
      return;
    }
    if (!response.ok) throw new Error('登录状态暂时不可用');
    const result = await response.json();
    updateState(result.state);
    if (result.state === 'APPROVED') {
      clearInterval(pollTimer);
      await showSession();
    }
    if (result.state === 'EXPIRED') clearInterval(pollTimer);
  } catch {
    statusText.textContent = '连接中断，正在重试…';
  } finally {
    polling = false;
  }
}

async function createQr() {
  clearInterval(pollTimer);
  qrOverlay.hidden = true;
  qrImage.style.visibility = 'visible';
  statusText.textContent = '正在准备安全二维码…';
  try {
    await readCsrf();
    const response = await request('/api/qr-sessions', { method: 'POST' });
    if (!response.ok) throw new Error('二维码创建失败');
    const result = await response.json();
    ticket = result.ticket;
    qrImage.src = `/api/qr-sessions/${encodeURIComponent(ticket)}/qr`;
    updateState('PENDING');
    pollTimer = setInterval(poll, 1200);
    await poll();
  } catch {
    statusText.textContent = '暂时无法生成二维码，请刷新页面重试';
  }
}

document.querySelector('#refresh-button').addEventListener('click', createQr);
document.querySelector('#logout-button').addEventListener('click', async () => {
  await readCsrf();
  const response = await request('/api/logout', { method: 'POST' });
  if (response.ok) window.location.reload();
});

createQr();
