// dashboard.js — fetch data and render charts

async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) {
    // If session/CSRF expired (common after backend devtools restart), refresh the page to get a new token
    if (res.status === 401 || res.status === 403) {
      showToast('Session expired. Refreshing…');
      setTimeout(()=> window.location.reload(), 600);
    }
    let detail = '';
    try { detail = await res.text(); } catch {}
    throw new Error(`${res.status} ${res.statusText}${detail ? ' - ' + detail.slice(0,200) : ''}`);
  }
  return res.json();
}

// Read CSRF token/header from meta tags (Thymeleaf provides them)
function getCsrf() {
  const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
  const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';
  return { header, token };
}

function formatMoney(amount) {
  if (amount == null) return '0';
  return Number(amount).toFixed(2);
}
function formatINR(amount) {
  try { return '₹' + (Number(amount || 0)).toFixed(2); } catch { return '₹0.00'; }
}

function clearCanvasWithMessage(canvas, message) {
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const width = canvas.offsetWidth || canvas.width || 320;
  const height = canvas.offsetHeight || canvas.height || 200;
  if (canvas.width !== width) canvas.width = width;
  if (canvas.height !== height) canvas.height = height;
  ctx.save();
  ctx.clearRect(0, 0, width, height);
  if (message) {
    ctx.fillStyle = '#6b7280';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(message, width / 2, height / 2);
  }
  ctx.restore();
}

function setVisualizationVisibility(show) {
  const rightPanel = document.querySelector('.right-panel');
  const charts = document.querySelector('.charts');
  const settlePanel = document.querySelector('.settlement-panel');
  const optimizeCard = document.querySelector('.optimize-card');
  const instructions = document.getElementById('instructions');
  const pieCanvas = document.getElementById('balancesPie');
  const txChartEl = document.getElementById('txChart');
  const settleCanvas = document.getElementById('settleGraph');
  if (show) {
    if (charts) charts.style.display = '';
    if (settlePanel) settlePanel.style.display = '';
    if (rightPanel) rightPanel.style.display = '';
    if (optimizeCard) optimizeCard.style.display = '';
    // Clear any placeholder text in instructions
    if (instructions && instructions.dataset.placeholder === 'true') {
      delete instructions.dataset.placeholder;
      instructions.innerHTML = '';
    }
    return;
  }

  // Show panels but render empty canvases (user asked for two empty graph blocks, not hidden panels)
  if (charts) charts.style.display = '';
  if (settlePanel) settlePanel.style.display = '';
  if (rightPanel) rightPanel.style.display = '';
  if (optimizeCard) optimizeCard.style.display = 'none';

  if (window._balancesPie) { window._balancesPie.destroy(); window._balancesPie = null; }
  if (window._txChart) { window._txChart.destroy(); window._txChart = null; }
  clearCanvasWithMessage(pieCanvas, 'No balances yet');
  clearCanvasWithMessage(txChartEl, 'No activity yet');
  if (settleCanvas) {
    const ctx = settleCanvas.getContext('2d');
    if (ctx) {
      const width = settleCanvas.width || settleCanvas.offsetWidth || 320;
      const height = settleCanvas.height || settleCanvas.offsetHeight || 240;
      ctx.clearRect(0, 0, width, height);
      ctx.save();
      ctx.fillStyle = '#9ca3af';
      ctx.font = '14px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('No settlements yet', width/2, height/2);
      ctx.restore();
    }
  }
  if (instructions) {
    instructions.dataset.placeholder = 'true';
    instructions.innerHTML = '<div class="empty">No settlement suggestions yet</div>';
  }
}

function escapeHtml(value) {
  if (!value) return '';
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function buildLimitedSummary(owe = [], receive = []) {
  let html = '<div class="limited-note" style="margin-bottom:10px;">You have been added to a shared group. Add your own transaction when you are ready, or settle the pending items below.</div>';
  if ((owe && owe.length) || (receive && receive.length)) {
    html += '<div class="limited-summary" style="margin-top:4px;padding:12px;border:1px solid #d1d5db;border-radius:6px;background:#f9fafb;">';
    html += '<div style="margin-bottom:8px;padding:6px;background:#fef3c7;border-radius:4px;font-size:12px;color:#92400e;">💡 <strong>Smart Optimization</strong>: These are optimized settlements (not raw transactions). Our algorithm minimized the number of payments needed!</div>';
    if (owe && owe.length) {
      html += '<div style="margin-bottom:10px;"><strong>Optimized Payments (You should pay)</strong><ul style="margin:6px 0 0 18px;">';
      owe.forEach(item => {
        html += `<li>Pay ${formatINR(item.amount)} to ${escapeHtml(item.toUser || '')} <span style="color:#059669;font-size:11px;">✓ Optimized</span></li>`;
      });
      html += '</ul></div>';
    }
    if (receive && receive.length) {
      html += '<div><strong>Optimized Receipts (You should receive)</strong><ul style="margin:6px 0 0 18px;">';
      receive.forEach(item => {
        html += `<li>${escapeHtml(item.fromUser || '')} should pay you ${formatINR(item.amount)} <span style="color:#059669;font-size:11px;">✓ Optimized</span></li>`;
      });
      html += '</ul></div>';
    }
    html += '</div>';
  }
  return html;
}

async function loadTransactions() {
  const container = document.getElementById('tx-list');
  try {
    const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
    const txEndpoint = isAdmin ? '/api/transactions' : '/api/transactions/me';
    let txs = await fetchJson(txEndpoint);
    // fallback: if user endpoint returns empty but global has data, fetch global for visibility
    if (!isAdmin && (!Array.isArray(txs) || txs.length === 0)) {
      try {
        const globalTx = await fetchJson('/api/transactions');
        if (Array.isArray(globalTx) && globalTx.length) {
          txs = globalTx.filter(t => {
            const u = document.querySelector('meta[name="current_user"]')?.getAttribute('content');
            if (!u) return false;
            const payer = (t.payerUsername||'').trim().toLowerCase();
            const payee = (t.payeeUsername||'').trim().toLowerCase();
            const me = u.trim().toLowerCase();
            return payer === me || payee === me;
          });
          console.debug('[DEBUG] using global fallback filter; originalGlobalCount=', globalTx.length, 'filteredCount=', txs.length);
        }
      } catch (e) { /* ignore */ }
    }
    container.innerHTML = '';
  const displayTxs = (Array.isArray(txs) ? txs : []).filter(t => typeof t.id === 'number' && t.id > 0);
  console.debug('[DEBUG] loadTransactions user=', document.querySelector('meta[name="current_user"]')?.getAttribute('content'), 'tx count=', displayTxs.length);

    if (!displayTxs.length) {
      container.innerHTML = '<div class="empty">No transactions yet</div>';
      // Keep graph blocks visible but empty (user requirement)
      setVisualizationVisibility(false);
      return displayTxs;
    }

    displayTxs.forEach(t => {
      const el = document.createElement('div');
      el.className = 'tx-row';

      const payer = escapeHtml(t.payerUsername || '');
      const payee = escapeHtml(t.payeeUsername || '');
      const canModify = typeof t.id === 'number' && t.id > 0;

      let actionsHtml;
      if (canModify) {
        actionsHtml = `<button class="btn btn-sm btn-primary" data-action="add" data-id="${t.id}">Add</button>
                       <button class="btn btn-sm btn-danger" data-action="delete" data-id="${t.id}">Delete</button>`;
      } else {
        actionsHtml = '<span class="tx-note">Shared reference (read-only)</span>';
      }

      el.innerHTML = `<div class="tx-left"><strong>From:</strong> ${payer}</div>
                      <div class="tx-mid"><strong>To:</strong> ${payee}</div>
                      <div class="tx-right">${formatMoney(t.amount)}</div>
                      <div class="tx-actions">${actionsHtml}</div>`;

      if (t.description) {
        const desc = document.createElement('div');
        desc.className = 'tx-description';
        desc.textContent = t.description;
        el.appendChild(desc);
      }

      if (canModify) {
        const addBtn = el.querySelector('button[data-action="add"]');
        const delBtn = el.querySelector('button[data-action="delete"]');

        addBtn.addEventListener('click', async () => {
          const uname = prompt('Enter registered username to add to this transaction:');
          if (!uname) return;
          const amtStr = prompt('Enter amount for this participant (leave empty to use 0):', (t.amount||0).toString());
          let amt = 0;
          try { amt = parseFloat(amtStr) || 0; } catch (e) { amt = 0; }
          if (!confirm(`Add ${uname} with amount ${amt} to transaction ${t.id}?`)) return;
          try {
            const csrf = getCsrf();
            const resp = await fetch(`/transactions/${t.id}/add-participant`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', [csrf.header]: csrf.token },
              body: JSON.stringify({ username: uname, amount: amt })
            });
            if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
            const json = await resp.json();
            if (json.status === 'ok') {
              showToast('Participant added and notification triggered');
              await loadTransactions();
              await loadBalancesAndRender();
              await loadAndRenderNetwork();
              await loadPersonalNotifications({ skipRefresh: true });
            } else {
              showToast('Add participant failed: ' + (json.message||'unknown'), true);
            }
          } catch (err) { showToast('Add participant failed: ' + err.message, true); console.error(err); }
        });

        delBtn.addEventListener('click', async () => {
          if (!confirm('Delete this transaction permanently?')) return;
          try {
            const csrf = getCsrf();
            const resp = await fetch(`/api/transactions/${t.id}`, { method: 'DELETE', headers: { [csrf.header]: csrf.token } });
            if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
            showToast('Transaction deleted');
            setNetworkMode('raw');
            await loadTransactions();
            await loadBalancesAndRender();
            await loadAndRenderNetwork();
            await loadPersonalNotifications({ skipRefresh: true });
          } catch (err) { showToast('Delete failed: ' + err.message, true); console.error(err); }
        });
      }

      container.appendChild(el);
    });

  // Show graphs when there is at least one real transaction
  const rightPanel = document.querySelector('.right-panel');
  const charts = document.querySelector('.charts');
  const settlePanel = document.querySelector('.settlement-panel');
  if (rightPanel) rightPanel.style.display = '';
  if (charts) charts.style.display = '';
  if (settlePanel) settlePanel.style.display = '';
  // Clear any placeholder state and enable real rendering
  setVisualizationVisibility(true);
    return displayTxs;
  } catch (err) {
    container.innerHTML = `<div class="error">Error loading transactions: ${err.message}</div>`;
    console.error(err);
    setVisualizationVisibility(false);
    return [];
  }
}

// Check whoami to detect stale page meta and help debugging
async function fetchWhoami() {
  try {
    const info = await fetchJson('/api/whoami');
    console.debug('[DEBUG] whoami', info);
    return info;
  } catch (err) {
    console.warn('whoami fetch failed', err);
    return null;
  }
}

// -- Personal settlement notifications --
async function loadPersonalNotifications(options = {}) {
  const { skipRefresh = false } = options;
  try {
    const payload = await fetchJson('/api/personal-notifications');
    const data = payload || { owe: [], receive: [], limitedView: false };
    const owe = Array.isArray(data.owe) ? data.owe : [];
    const receive = Array.isArray(data.receive) ? data.receive : [];
    const isLimited = !!data.limitedView;

    // API now returns { owe: [], receive: [], limitedView: boolean }
    renderNotificationBell(data);

    const charts = document.querySelector('.charts');
    const settlePanel = document.querySelector('.settlement-panel');
    const addBtn = document.getElementById('showAddForm');
    const txList = document.getElementById('tx-list');
    const hasRows = !!txList?.querySelector('.tx-row');

    if (isLimited && !hasRows) {
      setVisualizationVisibility(false);
      if (addBtn) addBtn.style.display = '';

      if (txList) {
        const summaryHtml = buildLimitedSummary(owe, receive);
        txList.innerHTML = `<div class="limited-banner">${summaryHtml}</div>`;
      }

      if (!skipRefresh) {
        await loadBalancesAndRender();
        await loadAndRenderNetwork();
      }
      return;
    }

    setVisualizationVisibility(true);
    if (isLimited && hasRows && txList) {
      const summaryHtml = buildLimitedSummary(owe, receive);
      let banner = txList.querySelector('.limited-banner');
      if (!banner) {
        banner = document.createElement('div');
        banner.className = 'limited-banner';
        txList.insertAdjacentElement('afterbegin', banner);
      }
      banner.innerHTML = summaryHtml;
    } else if (txList) {
      const banner = txList.querySelector('.limited-banner');
      if (banner) banner.remove();
    }

    if (addBtn) addBtn.style.display = '';

    if (!skipRefresh) {
      await loadTransactions();
      await loadBalancesAndRender();
      await loadAndRenderNetwork();
    }
  } catch (err) {
    console.warn('Failed to load personal notifications', err);
  }
}

function renderNotificationBell(data) {
  // attach to header actions if present
  const headerActions = document.querySelector('.header-actions');
  if (!headerActions) return;

  const owe = data?.owe || [];
  const receive = data?.receive || [];
  const totalCount = owe.length + receive.length;

  // remove existing bell if any
  const existing = document.getElementById('personalBell');
  if (existing) {
    if (existing._docListener) document.removeEventListener('click', existing._docListener);
    existing.remove();
  }

  const wrapper = document.createElement('div');
  wrapper.id = 'personalBell';
  wrapper.className = 'personal-bell';

  const btn = document.createElement('button');
  btn.className = 'btn btn-ghost small';
  btn.title = 'Optimized Settlements (click to view smart payment suggestions)';
  btn.innerHTML = `🔔 <span id="ps-badge" style="color:#fff;background:#ef4444;border-radius:10px;padding:2px 6px;margin-left:6px;display:inline-block;${totalCount? '':'display:none;'}">${totalCount}</span>`;
  wrapper.appendChild(btn);

  const dropdown = document.createElement('div');
  dropdown.id = 'ps-dropdown';
  dropdown.className = 'ps-dropdown';
  dropdown.style.display = 'none';
  dropdown.style.position = 'absolute';
  dropdown.style.background = '#fff';
  dropdown.style.border = '1px solid #ddd';
  dropdown.style.padding = '8px';
  dropdown.style.right = '10px';
  dropdown.style.top = '48px';
  dropdown.style.zIndex = 1000;

  if (!totalCount) {
    dropdown.innerHTML = '<div class="empty" style="padding:12px;color:#6b7280;">🎉 All settled!<br><span style="font-size:11px;">No optimized payments needed right now.</span></div>';
  } else {
    // Add optimization info header
    const infoHeader = document.createElement('div');
    infoHeader.style.padding = '8px';
    infoHeader.style.background = '#ecfdf5';
    infoHeader.style.borderRadius = '4px';
    infoHeader.style.marginBottom = '8px';
    infoHeader.style.fontSize = '11px';
    infoHeader.style.color = '#065f46';
    infoHeader.innerHTML = '💡 <strong>Smart Settlements</strong>: These are optimized using our greedy algorithm to minimize transactions!';
    dropdown.appendChild(infoHeader);
    const section = (title, items, formatter) => {
      if (!items || !items.length) return null;
      const block = document.createElement('div');
      const heading = document.createElement('div');
      heading.style.fontWeight = '700';
      heading.style.margin = '4px 0 6px';
      heading.style.fontSize = '13px';
      heading.innerHTML = title + ' <span style="color:#059669;font-size:10px;font-weight:500;">✓ OPTIMIZED</span>';
      block.appendChild(heading);
      const ul = document.createElement('ul');
      ul.style.listStyle = 'none';
      ul.style.margin = 0;
      ul.style.padding = 0;
      items.forEach(it => {
        const li = document.createElement('li');
        li.style.display = 'flex';
        li.style.justifyContent = 'space-between';
        li.style.alignItems = 'center';
        li.style.padding = '6px 4px';
        li.innerHTML = `<div>${formatter(it)}</div>`;
  const settleBtn = document.createElement('button');
  settleBtn.className = 'btn btn-sm btn-success';
  settleBtn.textContent = 'Mark as Settled';
        settleBtn.addEventListener('click', async () => {
          try {
            const csrf = getCsrf();
            settleBtn.disabled = true;
            settleBtn.textContent = 'Settling...';
            const resp = await fetch(`/api/personal/${it.id}/settle`, { method: 'POST', headers: { [csrf.header]: csrf.token } });
            if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
            const json = await resp.json();
            if (json.status === 'ok') {
              const badge = document.getElementById('ps-badge');
              const newCount = Math.max(0, (parseInt(badge.textContent||'0')||0) - 1);
              badge.textContent = newCount;
              if (newCount === 0) badge.style.display = 'none';
              if (li.parentElement) li.remove();
              await loadPersonalNotifications({ skipRefresh: true });
              await loadBalancesAndRender();
              await loadAndRenderNetwork();
            } else {
              showToast('Failed to mark settled', true);
              settleBtn.disabled = false;
              settleBtn.textContent = 'Mark as Settled';
            }
          } catch (err) {
            showToast('Mark settled failed: ' + err.message, true);
            console.error(err);
            settleBtn.disabled = false;
            settleBtn.textContent = 'Mark as Settled';
          }
        });
        li.appendChild(settleBtn);
        ul.appendChild(li);
      });
      block.appendChild(ul);
      return block;
    };

    const currentUser = document.querySelector('meta[name="current_user"]')?.getAttribute('content');
    const oweBlock = section('You need to pay', owe, it => `You owe <strong>${formatINR(it.amount)}</strong> to <strong>${it.toUser}</strong>`);
    const receiveBlock = section('You are owed', receive, it => `<strong>${it.fromUser}</strong> owes you <strong>${formatINR(it.amount)}</strong>`);
    if (oweBlock) dropdown.appendChild(oweBlock);
    if (receiveBlock) dropdown.appendChild(receiveBlock);
  }

  btn.addEventListener('click', () => {
    dropdown.style.display = dropdown.style.display === 'none' ? 'block' : 'none';
  });

  // close dropdown when clicking outside
  const closeOnOutside = (ev) => {
    if (!wrapper.contains(ev.target)) dropdown.style.display = 'none';
  };
  document.addEventListener('click', closeOnOutside);
  wrapper._docListener = closeOnOutside;

  wrapper.appendChild(dropdown);
  headerActions.insertBefore(wrapper, headerActions.firstChild);
}

async function loadBalancesAndRender() {
  try {
    const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
    const balEndpoint = isAdmin ? '/api/balances' : '/api/balances/me';
    const balances = await fetchJson(balEndpoint);
    const pieCanvas = document.getElementById('balancesPie');
    const txChartEl = document.getElementById('txChart');
    const chartsWrapper = document.querySelector('.charts');

    const cleanedBalances = Array.isArray(balances)
      ? balances.filter(b => Math.abs(Number(b.balance || 0)) > 0.00001)
      : [];
    const hasBalances = cleanedBalances.length > 0;

    if (!hasBalances) {
      if (window._balancesPie) { window._balancesPie.destroy(); window._balancesPie = null; }
      if (window._txChart) { window._txChart.destroy(); window._txChart = null; }
      clearCanvasWithMessage(pieCanvas, 'No balances yet');
      clearCanvasWithMessage(txChartEl, 'No activity yet');
      renderInstructions([]);
      return;
    }

    if (chartsWrapper) chartsWrapper.style.display = '';

    const labels = cleanedBalances.map(b => b.username);
    const signedValues = cleanedBalances.map(b => Number(b.balance || 0));
    const data = signedValues.map(v => Math.abs(v));

    // Pie chart
    if (!pieCanvas) return;
    const pieCtx = pieCanvas.getContext('2d');
    if (window._balancesPie) window._balancesPie.destroy();
    window._balancesPie = new Chart(pieCtx, {
      type: 'pie',
      data: {
        labels: labels,
        datasets: [{
          data: data,
          backgroundColor: generateColors(data.length),
          borderColor: '#ffffff',
          borderWidth: 1.5,
          hoverOffset: 18
        }]
      },
      options: {
        responsive: true,
        plugins: {
          tooltip: {
            callbacks: {
              label: (context) => {
                const label = context.label || '';
                const original = signedValues[context.dataIndex] ?? 0;
                const formatted = formatINR(original);
                const direction = original >= 0 ? 'net positive' : 'net negative';
                return `${label}: ${formatted} (${direction})`;
              }
            }
          }
        }
      }
    });

    // Transactions over time chart removed; skip rendering if element missing
    if (txChartEl) {
      const txEndpointForChart = isAdmin ? '/api/transactions' : '/api/transactions/me';
      const txs = await fetchJson(txEndpointForChart);
      const byDate = {};
      txs.forEach(t => {
        const d = new Date(t.timestamp || Date.now());
        const key = d.toISOString().slice(0,10);
        byDate[key] = (byDate[key]||0) + Number(t.amount || 0);
      });
      const dates = Object.keys(byDate).sort();
      const values = dates.map(k => byDate[k]);
      const lineCtx = txChartEl.getContext('2d');
      if (window._txChart) window._txChart.destroy();
      window._txChart = new Chart(lineCtx, {
        type: 'line',
        data: { labels: dates, datasets: [{ label: 'Amount', data: values, borderColor: '#2563eb', backgroundColor: 'rgba(37,99,235,0.08)' }] },
        options: { responsive: true }
      });
    }

  } catch (err) {
    console.error('Error rendering charts', err);
  }
}

function generateColors(n) {
  const palette = ['#2563eb','#10b981','#f59e0b','#ef4444','#8b5cf6','#06b6d4','#f97316','#6366f1'];
  const out = [];
  for (let i=0;i<n;i++) out.push(palette[i % palette.length]);
  return out;
}

function setNetworkMode(mode) {
  window._networkMode = mode === 'optimized' ? 'optimized' : 'raw';
  const btn = document.getElementById('optimizeBtn');
  if (btn) {
    if (window._networkMode === 'optimized') {
      btn.textContent = 'Back to Current';
      btn.dataset.mode = 'optimized';
    } else {
      const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
      btn.textContent = isAdmin ? 'Optimize' : 'Review Settlements';
      btn.dataset.mode = 'raw';
    }
  }
}

async function fetchCurrentTxs(isAdmin) {
  const txEndpoint = isAdmin ? '/api/transactions' : '/api/transactions/me';
  const txs = await fetchJson(txEndpoint);
  return Array.isArray(txs) ? txs : [];
}

// Utility: small delay
function sleep(ms){ return new Promise(r=>setTimeout(r, ms)); }

// After saving, wait briefly for backend to reflect new tx in /me list
async function waitForTxPresence({ isAdmin, txId, maxAttempts = 4, delayMs = 250 }) {
  if (!txId) return false;
  for (let i = 0; i < maxAttempts; i++) {
    try {
      const txs = await fetchCurrentTxs(isAdmin);
      if (txs && txs.some(t => Number(t.id) === Number(txId))) return true;
    } catch (_) {}
    await sleep(delayMs);
  }
  return false;
}

function buildBalancesFromTxs(txs) {
  // payer paid amount (credit), payee owes amount (debit)
  const map = new Map();
  (txs||[]).forEach(t => {
    const payer = (t.payerUsername||'').trim();
    const payee = (t.payeeUsername||'').trim();
    const amt = Number(t.amount||0);
    if (!payer || !payee || !amt) return;
    map.set(payer, (map.get(payer)||0) + amt);
    map.set(payee, (map.get(payee)||0) - amt);
  });
  return map;
}

function optimizeSettlementsFromBalances(balanceMap) {
  const positives = [];
  const negatives = [];
  for (const [user, bal] of balanceMap.entries()) {
    if (Math.abs(bal) < 1e-9) continue;
    if (bal > 0) positives.push({ user, bal }); else negatives.push({ user, bal });
  }
  positives.sort((a,b)=> b.bal - a.bal);
  negatives.sort((a,b)=> a.bal - b.bal); // more negative first
  const edges = [];
  const instr = [];
  let i=0, j=0;
  while (i<positives.length && j<negatives.length) {
    const pos = positives[i];
    const neg = negatives[j];
    const owe = Math.min(pos.bal, Math.abs(neg.bal));
    if (owe <= 1e-9) { if (pos.bal <= 1e-9) i++; if (Math.abs(neg.bal) <= 1e-9) j++; continue; }
    // neg owes pos
    edges.push({ from: neg.user, to: pos.user, amount: owe });
    instr.push(`${pos.user} receives ${formatINR(owe)} from ${neg.user}`);
    pos.bal -= owe;
    neg.bal += owe;
    if (pos.bal <= 1e-9) i++;
    if (Math.abs(neg.bal) <= 1e-9) j++;
  }
  return { edges, instructions: instr };
}

async function optimize(isAdmin) {
  try {
    // Toggle: if already optimized, go back to raw
    if (window._networkMode === 'optimized') {
      setNetworkMode('raw');
      renderInstructions([]);
      await loadAndRenderNetwork();
      return;
    }

    // Compute optimized from CURRENT transactions shown to this user (admin=global, else per-user)
    const txs = await fetchCurrentTxs(isAdmin);
    const balances = buildBalancesFromTxs(txs);
    const { edges, instructions } = optimizeSettlementsFromBalances(balances);
    window._optimizedEdges = edges; // cache for network renderer
    renderInstructions(instructions);
    setNetworkMode('optimized');
    await loadAndRenderNetwork();
    showToast('Optimization applied');
  } catch (err) { showToast('Optimize failed: ' + err.message, true); }
}

async function settle() {
  // settle endpoint is removed from UI — kept for compatibility but not wired in UI
  try {
    const resp = await fetch('/settle', { method: 'POST' });
    const json = await resp.json();
    if (resp.ok) renderInstructions(json.instructions);
  } catch (err) { console.warn('Settle failed (not shown in UI):', err); }
}

function renderInstructions(instr) {
  const container = document.getElementById('instructions');
  if (!instr || instr.length === 0) {
    container.innerHTML = '<div class="empty">No settlement required</div>';
    return;
  }
  container.innerHTML = '';
  instr.forEach(i => {
    const item = document.createElement('div');
    item.className = 'instruction-item';
    item.textContent = i;
    container.appendChild(item);
  });
}

// Force-directed interactive network renderer with dragging and hover tooltip
// Replaces the older circular renderer. Uses a simple N^2 repulsion + spring links
// which is fine for small networks (dozens of nodes). Keeps HiDPI crispness.
const NetworkSim = {
  canvas: null,
  ctx: null,
  dpr: 1,
  nodes: [],
  edges: [],
  animationId: null,
  dragging: null,
  hoverNode: null,
  highlightedNode: null,
  running: true,
  tooltip: null,
  staticLayout: false,
};

function createOrGetTooltip() {
  let t = document.querySelector('.graph-tooltip');
  if (!t) {
    t = document.createElement('div');
    t.className = 'graph-tooltip';
    t.style.position = 'absolute';
    t.style.pointerEvents = 'none';
    t.style.display = 'none';
    t.style.padding = '6px 8px';
    t.style.background = 'rgba(255,255,255,0.98)';
    t.style.border = '1px solid rgba(0,0,0,0.08)';
    t.style.borderRadius = '6px';
    t.style.boxShadow = '0 4px 12px rgba(0,0,0,0.06)';
  t.style.fontSize = '5px';
    t.style.color = '#111827';
    document.body.appendChild(t);
  }
  return t;
}

function stopNetwork() {
  if (NetworkSim.animationId) cancelAnimationFrame(NetworkSim.animationId);
  NetworkSim.animationId = null;
  const canvas = NetworkSim.canvas;
  if (canvas) {
    canvas.onmousedown = null;
    canvas.onmousemove = null;
    canvas.onmouseup = null;
    canvas.onmouseleave = null;
  }
}

async function loadAndRenderNetwork() {
  try {
    const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
    const mode = window._networkMode || 'raw';
    let edges = [];
    const txs = await fetchCurrentTxs(isAdmin);
    if (mode === 'optimized') {
      if (Array.isArray(window._optimizedEdges)) {
        edges = window._optimizedEdges;
      } else {
        const balances = buildBalancesFromTxs(txs);
        const result = optimizeSettlementsFromBalances(balances);
        edges = result.edges;
      }
    } else {
      // raw: build edges from current transactions: payee -> payer (who owes whom), aggregated by pair
      const agg = new Map();
      (txs || []).forEach(t => {
        const payer = (t.payerUsername||'').trim();
        const payee = (t.payeeUsername||'').trim();
        const amt = Number(t.amount||0);
        if (!payer || !payee || !amt) return;
        const from = payee; // owes
        const to = payer;   // is owed
        const key = from + '->' + to;
        agg.set(key, (agg.get(key)||0) + amt);
      });
      edges = Array.from(agg.entries()).map(([k,v]) => {
        const [from,to] = k.split('->');
        return { from, to, amount: v };
      });
    }
    const canvas = document.getElementById('settleGraph');
    if (!canvas) return;

    stopNetwork();

    const ctx = canvas.getContext('2d');
    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    NetworkSim.canvas = canvas;
    NetworkSim.ctx = ctx;
    NetworkSim.dpr = dpr;

    canvas.width = Math.max(300, Math.floor(rect.width * dpr));
    canvas.height = Math.max(200, Math.floor((parseInt(getComputedStyle(canvas).height) || 320) * dpr));
  canvas.style.width = rect.width + 'px';
  canvas.style.height = (canvas.height / dpr) + 'px';
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const width = rect.width;
  const height = canvas.height / dpr;

  ctx.clearRect(0, 0, width, height);

    // Guard: only render a graph if there is at least one real transaction row present
    const hasRealTx = !!document.querySelector('#tx-list .tx-row');
    if (!hasRealTx || !edges || edges.length === 0) {
      // empty placeholder
      ctx.fillStyle = '#6b7280';
      ctx.font = '14px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const placeholder = (mode === 'optimized') ? 'No settlements yet' : 'No transactions yet';
      ctx.fillText(placeholder, width/2, height/2);
      if (mode === 'raw') renderInstructions([]);
      return;
    }

  // Build nodes and links
    const nodesMap = {};
    edges.forEach(e => { nodesMap[e.from] = nodesMap[e.from] || { id: e.from }; nodesMap[e.to] = nodesMap[e.to] || { id: e.to }; });
    const nodes = Object.values(nodesMap);
    const links = edges.map(e => ({ source: e.from, target: e.to, amount: Number(e.amount || 0) }));

    // initialize positions (if previously existed try to reuse)
    const prev = {};
    NetworkSim.nodes.forEach(n => prev[n.id] = n);
    const scoreMap = new Map();
    const incomingTotals = new Map();
    const targetsBySource = new Map();
    links.forEach(l => {
      const amt = Math.abs(Number(l.amount) || 0);
      scoreMap.set(l.source, (scoreMap.get(l.source) || 0) - amt);
      scoreMap.set(l.target, (scoreMap.get(l.target) || 0) + amt);
      incomingTotals.set(l.target, (incomingTotals.get(l.target) || 0) + amt);
      const list = targetsBySource.get(l.source) || [];
      list.push({ id: l.target, weight: amt });
      targetsBySource.set(l.source, list);
    });

    const leftNodes = [];
    const rightNodes = [];
    const neutralNodes = [];
    nodes.forEach(n => {
      const score = scoreMap.get(n.id) || 0;
      if (score > 1e-6) rightNodes.push(n);
      else if (score < -1e-6) leftNodes.push(n);
      else neutralNodes.push(n);
    });

    rightNodes.sort((a, b) => {
      const wA = incomingTotals.get(a.id) || 0;
      const wB = incomingTotals.get(b.id) || 0;
      if (wA === wB) return a.id.localeCompare(b.id);
      return wB - wA;
    });

    const rightIndex = new Map();
    rightNodes.forEach((n, idx) => rightIndex.set(n.id, idx));

    const leftAvgCache = new Map();
    const computeLeftAvg = (node) => {
      if (leftAvgCache.has(node.id)) return leftAvgCache.get(node.id);
      const targets = targetsBySource.get(node.id) || [];
      if (!targets.length || rightNodes.length === 0) {
        const fallback = rightNodes.length ? rightNodes.length / 2 : 0;
        leftAvgCache.set(node.id, fallback);
        return fallback;
      }
      let totalWeight = 0;
      let weighted = 0;
      targets.forEach(t => {
        const idx = rightIndex.get(t.id);
        if (idx == null) return;
        weighted += (idx + 1) * t.weight;
        totalWeight += t.weight;
      });
      const avg = totalWeight ? (weighted / totalWeight) : (rightNodes.length / 2);
      leftAvgCache.set(node.id, avg);
      return avg;
    };

    leftNodes.sort((a, b) => {
      const avgA = computeLeftAvg(a);
      const avgB = computeLeftAvg(b);
      if (avgA === avgB) return a.id.localeCompare(b.id);
      return avgA - avgB;
    });

    neutralNodes.sort((a, b) => a.id.localeCompare(b.id));

    const positions = new Map();
    const clampY = (y) => Math.min(height - 40, Math.max(40, y));
    const placeLinear = (list, x) => {
      const spacing = height / (list.length + 1 || 1);
      list.forEach((node, idx) => {
        positions.set(node.id, {
          x,
          y: clampY(spacing * (idx + 1))
        });
      });
    };

    const useBipartite = leftNodes.length > 0 && rightNodes.length > 0;
    if (useBipartite) {
      // push left and right lanes slightly further apart so links appear a bit longer
      const laneLeft = Math.min(width * 0.32, Math.max(100, width * 0.18));
      const laneRight = width - laneLeft;
      placeLinear(leftNodes, laneLeft);
      placeLinear(rightNodes, laneRight);
      if (neutralNodes.length) {
        placeLinear(neutralNodes, width / 2);
      }
    } else {
      const cx = width / 2;
      const cy = height / 2;
      // increase ring radius slightly so nodes sit further from center and edges grow a bit
      const ring = Math.max(120, Math.min(cx, cy) - 60);
      const sortedIds = nodes.map(n => n.id).sort();
      const orderMap = new Map(sortedIds.map((id, i) => [id, i]));
      nodes.forEach(n => {
        const idx = orderMap.get(n.id) || 0;
        const angle = (idx / nodes.length) * Math.PI * 2 - Math.PI / 2;
        positions.set(n.id, {
          x: cx + ring * Math.cos(angle),
          y: cy + ring * Math.sin(angle)
        });
      });
    }

    NetworkSim.nodes = nodes.map(n => {
      const reuse = prev[n.id];
      const pos = positions.get(n.id) || { x: width / 2, y: height / 2 };
      const nodeData = {
        id: n.id,
        x: pos.x,
        y: pos.y,
        vx: 0,
        vy: 0,
        fx: 0,
        fy: 0,
        fixed: true,
        radius: 12
      };
      return reuse ? Object.assign(reuse, nodeData) : nodeData;
    });

    links.forEach(l => {
      const start = positions.get(l.source);
      const end = positions.get(l.target);
      if (start && end) {
        const dy = end.y - start.y;
        l._direction = Math.abs(dy) < 12 ? 1 : (dy > 0 ? 1 : -1);
      } else {
        l._direction = 1;
      }
    });

    NetworkSim.edges = links;
    NetworkSim.tooltip = createOrGetTooltip();

    // compute amount scale for visuals
    const maxAmt = Math.max(...links.map(l => Math.abs(l.amount)), 1);
    NetworkSim.maxAmt = maxAmt;

  // use static layout — no dragging/physics to match screenshot
  NetworkSim.staticLayout = true;
  canvas.onmousedown = null;
  canvas.onmousemove = null;
  canvas.onmouseup = null;
  canvas.onmouseleave = null;

    // wire stabilize button (if present)
    const stab = document.getElementById('stabilizeBtn');
    if (stab) {
      stab.textContent = NetworkSim.running ? 'Stabilize' : 'Resume';
      stab.onclick = () => {
        NetworkSim.running = !NetworkSim.running;
        stab.textContent = NetworkSim.running ? 'Stabilize' : 'Resume';
        if (NetworkSim.running) {
          // resume animation
          if (!NetworkSim.animationId) NetworkSim.animationId = requestAnimationFrame(function tick() { simulateStep(rect); if (NetworkSim.running) NetworkSim.animationId = requestAnimationFrame(tick); });
        } else {
          // pause animation
          if (NetworkSim.animationId) cancelAnimationFrame(NetworkSim.animationId);
          NetworkSim.animationId = null;
        }
      };
    }

    // render once (no animation for static layout)
    NetworkSim.running = false;
    renderNetwork();

  } catch (err) {
    console.error('Error rendering network', err);
  }
}

function attachCanvasHandlers(canvas) {
  canvas.onmousedown = (ev) => {
    const p = getMousePos(ev);
    const n = findNodeAt(p.x, p.y);
    if (n) {
      NetworkSim.dragging = { node: n, ox: p.x - n.x, oy: p.y - n.y };
      n.fixed = true;
      n.vx = 0; n.vy = 0;
    }
  };
  canvas.onmousemove = (ev) => {
    const p = getMousePos(ev);
    if (NetworkSim.dragging) {
      const n = NetworkSim.dragging.node;
      n.x = p.x - NetworkSim.dragging.ox;
      n.y = p.y - NetworkSim.dragging.oy;
      n.vx = 0; n.vy = 0;
      renderNetwork();
      return;
    }
    const hover = findNodeAt(p.x, p.y);
    if (hover !== NetworkSim.hoverNode) {
      NetworkSim.hoverNode = hover;
      NetworkSim.highlightedNode = hover; // used for linked highlight
      if (!hover) NetworkSim.tooltip.style.display = 'none';
    }
    if (hover) {
      NetworkSim.tooltip.style.display = 'block';
      NetworkSim.tooltip.textContent = hover.id;
      NetworkSim.tooltip.style.left = (ev.clientX + 12) + 'px';
      NetworkSim.tooltip.style.top = (ev.clientY + 12) + 'px';
    }
  };
  // double click to pin/unpin node
  canvas.ondblclick = (ev) => {
    const p = getMousePos(ev);
    const n = findNodeAt(p.x, p.y);
    if (n) {
      n.fixed = !n.fixed;
      n.pinned = !!n.fixed;
      renderNetwork();
    }
  };
  canvas.onmouseup = () => {
    if (NetworkSim.dragging) {
      NetworkSim.dragging.node.fixed = false;
      NetworkSim.dragging = null;
    }
  };
  canvas.onmouseleave = () => {
    if (NetworkSim.dragging) {
      NetworkSim.dragging.node.fixed = false;
      NetworkSim.dragging = null;
    }
    NetworkSim.hoverNode = null;
    if (NetworkSim.tooltip) NetworkSim.tooltip.style.display = 'none';
  };
}

function getMousePos(ev) {
  const rect = NetworkSim.canvas.getBoundingClientRect();
  const dpr = NetworkSim.dpr || 1;
  const x = (ev.clientX - rect.left);
  const y = (ev.clientY - rect.top);
  return { x, y };
}

function findNodeAt(x, y) {
  for (let i = NetworkSim.nodes.length - 1; i >= 0; i--) {
    const n = NetworkSim.nodes[i];
    const dx = x - n.x, dy = y - n.y;
    if (dx*dx + dy*dy <= (n.radius+6)*(n.radius+6)) return n;
  }
  return null;
}

function simulateStep(rect) {
  const nodes = NetworkSim.nodes;
  const links = NetworkSim.edges;
  const ctx = NetworkSim.ctx;
  if (!nodes || !links) return;

  // reset forces
  for (const n of nodes) { n.fx = 0; n.fy = 0; }

  // repulsive forces (simple O(n^2))
  const kRepel = 6000; // reduced a bit for a tighter layout
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i+1; j < nodes.length; j++) {
      const a = nodes[i], b = nodes[j];
      let dx = a.x - b.x, dy = a.y - b.y;
      let dist2 = dx*dx + dy*dy + 0.01;
      let dist = Math.sqrt(dist2);
      const force = kRepel / dist2;
      const ux = dx / dist, uy = dy / dist;
      a.fx += ux * force;
      a.fy += uy * force;
      b.fx -= ux * force;
      b.fy -= uy * force;
    }
  }

  // spring forces for links
  const kSpring = 0.08; // spring constant
  // increase base link length so edges are longer (less cramped)
  // smaller divisor -> longer base length on most canvases
  const baseLen = Math.min(rect.width, rect.height) / 0.45;
  for (const l of links) {
    const s = nodes.find(n=>n.id===l.source);
    const t = nodes.find(n=>n.id===l.target);
    if (!s || !t) continue;
    let dx = t.x - s.x, dy = t.y - s.y;
    let dist = Math.sqrt(dx*dx + dy*dy) || 1;
    const desired = baseLen * (0.6 + 0.4 * (1 - Math.min(1, Math.abs(l.amount || 0) / NetworkSim.maxAmt)));
    const stretch = dist - desired;
    const fx = (dx / dist) * (kSpring * stretch);
    const fy = (dy / dist) * (kSpring * stretch);
    s.fx += fx; s.fy += fy;
    t.fx -= fx; t.fy -= fy;
  }

  // integrate velocities
  // gentle centering force to keep the graph neat in the middle
  const kCenter = 0.02;
  const cx = rect.width/2;
  const cy = (NetworkSim.canvas.height/NetworkSim.dpr)/2;

  const damping = 0.82;
  for (const n of nodes) {
    // apply center pull (unless manually fixed)
    if (!n.fixed) {
      n.fx += (cx - n.x) * kCenter;
      n.fy += (cy - n.y) * kCenter;
    }
    if (n.fixed) continue; // pinned while dragging
    // acceleration = force (mass=1)
    n.vx = (n.vx + n.fx * 0.016) * damping;
    n.vy = (n.vy + n.fy * 0.016) * damping;
    // limit velocity
    const vmax = 120;
    const vlen = Math.sqrt(n.vx*n.vx + n.vy*n.vy) || 1;
    if (vlen > vmax) { n.vx = (n.vx / vlen) * vmax; n.vy = (n.vy / vlen) * vmax; }
    n.x += n.vx * 0.016;
    n.y += n.vy * 0.016;
    // bound to canvas
    const pad = 30;
    n.x = Math.max(pad, Math.min(rect.width - pad, n.x));
    n.y = Math.max(pad, Math.min((NetworkSim.canvas.height/NetworkSim.dpr) - pad, n.y));
  }

  renderNetwork();
}

function renderNetwork() {
  const canvas = NetworkSim.canvas;
  const ctx = NetworkSim.ctx;
  const rect = canvas.getBoundingClientRect();
  ctx.clearRect(0, 0, rect.width, canvas.height / NetworkSim.dpr);

  const nodes = NetworkSim.nodes;
  const links = NetworkSim.edges;
  // nodeR is per-node (use n.radius) - default for fallback
  const defaultR = 14;
    const labelFont = '13px sans-serif';
    const nodeDims = {};
    ctx.save();
    ctx.font = labelFont;
    nodes.forEach(n => {
      const label = (n.id || '').toString().split(/\s+/)[0];
      const padX = 14;
      const height = 26;
      const width = Math.max(34, Math.ceil(ctx.measureText(label).width) + padX * 2);
      nodeDims[n.id] = { width, height };
    });
    ctx.restore();

  // draw links (curved thin lines like the screenshot)
  for (const l of links) {
    const a = nodes.find(n=>n.id===l.source);
    const b = nodes.find(n=>n.id===l.target);
    if (!a || !b) continue;
    const dx = b.x - a.x, dy = b.y - a.y;
    const dist = Math.sqrt(dx*dx + dy*dy) || 1;
    const txu = dx / dist, tyu = dy / dist; // unit tangent
    const nx = -dy / dist, ny = dx / dist;  // unit normal
    // respect node radius when computing endpoints
      const dimsA = nodeDims[a.id] || { width: defaultR * 2, height: defaultR * 2 };
      const dimsB = nodeDims[b.id] || { width: defaultR * 2, height: defaultR * 2 };
      const rxA = dimsA.width / 2;
      const ryA = dimsA.height / 2;
      const rxB = dimsB.width / 2;
      const ryB = dimsB.height / 2;
      const denomA = Math.sqrt((txu*txu)/(rxA*rxA) + (tyu*tyu)/(ryA*ryA)) || 1;
      const denomB = Math.sqrt((txu*txu)/(rxB*rxB) + (tyu*tyu)/(ryB*ryB)) || 1;
      const dA = 1 / denomA;
      const dB = 1 / denomB;
    const extraStart = 16;
    const arrowSize = 18;
    const gapToNode = 24;
    const padEnd = gapToNode + arrowSize;

    let startOffset = dA + extraStart;
    let endOffset = dB + padEnd;
    const minSpan = 40;
    const available = dist - (startOffset + endOffset);
    if (available < minSpan) {
      const deficit = minSpan - available;
      const minStart = dA + 6;
      const minEnd = dB + 8;
      const reduceStart = Math.min(startOffset - minStart, deficit / 2);
      startOffset -= reduceStart;
      const remaining = deficit - reduceStart;
      const reduceEnd = Math.min(endOffset - minEnd, remaining);
      endOffset -= reduceEnd;
    }

    const startX = a.x + txu * startOffset;
    const startY = a.y + tyu * startOffset;
    const endX = b.x - txu * endOffset;
    const endY = b.y - tyu * endOffset;
  // curve control point (normal offset)
  const baseCurve = Math.min(80, Math.max(12, dist / 8));
  const curveOffset = baseCurve * (l._direction || 1);
  const mx = (startX + endX) / 2 + nx * curveOffset;
  const my = (startY + endY) / 2 + ny * curveOffset;

  const isHighlighted = NetworkSim.highlightedNode && (NetworkSim.highlightedNode.id === a.id || NetworkSim.highlightedNode.id === b.id);
    // choose a simple color mapping like the screenshot
    const amt = Math.abs(l.amount);
    let color = '#10b981'; // green
    if (amt >= NetworkSim.maxAmt * 0.66) color = '#ef4444'; // red for largest
    else if (amt >= NetworkSim.maxAmt * 0.33) color = '#f59e0b'; // amber mid

  // base subtle stroke
  ctx.beginPath();
  ctx.moveTo(startX, startY);
  ctx.quadraticCurveTo(mx, my, endX, endY);
  ctx.strokeStyle = isHighlighted ? 'rgba(2,6,23,0.18)' : 'rgba(15,23,42,0.1)';
  ctx.lineWidth = 0.8;
    ctx.lineCap = 'round';
    ctx.stroke();

  // main colored stroke (thin)
  ctx.beginPath();
  ctx.moveTo(startX, startY);
  ctx.quadraticCurveTo(mx, my, endX, endY);
  ctx.strokeStyle = color;
  ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.shadowBlur = 0;
    ctx.stroke();

    // arrowhead
  // use curve tangent near end for arrow direction
  const tx = (endX - mx) * 2;
  const ty = (endY - my) * 2;
    const tlen = Math.sqrt(tx*tx + ty*ty) || 1;
  const dirX = b.x - endX;
  const dirY = b.y - endY;
  const dirLen = Math.sqrt(dirX*dirX + dirY*dirY) || 1;
  const au = dirX / dirLen;
  const av = dirY / dirLen;
  const wing = arrowSize * 0.55;
  const tipX = endX + au * arrowSize;
  const tipY = endY + av * arrowSize;
  const leftX = tipX - au * arrowSize - av * wing;
  const leftY = tipY - av * arrowSize + au * wing;
  const rightX = tipX - au * arrowSize + av * wing;
  const rightY = tipY - av * arrowSize - au * wing;
  ctx.beginPath();
  ctx.moveTo(tipX, tipY);
    ctx.lineTo(leftX, leftY);
    ctx.lineTo(rightX, rightY);
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.fill();
  ctx.lineWidth = 1.2;
  ctx.strokeStyle = 'rgba(15,23,42,0.6)';
  ctx.stroke();

    // amount label
    const t = 0.5;
    const qx = (1-t)*(1-t)*startX + 2*(1-t)*t*mx + t*t*endX;
    const qy = (1-t)*(1-t)*startY + 2*(1-t)*t*my + t*t*endY;
  const labelCx = qx + nx * 12;
  const labelCy = qy + ny * 12;
    const amountText = formatINR(l.amount);
  ctx.save();
  ctx.font = 'bold 12px sans-serif';
  const labelWidth = ctx.measureText(amountText).width + 20;
  const labelHeight = 28;
  const labelX = labelCx - labelWidth/2;
  const labelY = labelCy - labelHeight/2;
  ctx.fillStyle = 'rgba(255,255,255,0.92)';
  ctx.strokeStyle = 'rgba(148,163,184,0.55)';
  ctx.lineWidth = 1;
  roundRect(ctx, labelX, labelY, labelWidth, labelHeight, 14);
  ctx.fill();
  ctx.stroke();
  ctx.restore();

  ctx.save();
  ctx.fillStyle = '#0f172a';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = 'bold 12px sans-serif';
  ctx.fillText(amountText, labelCx, labelCy);
  ctx.restore();
  }

  // draw nodes (on top) styled like the screenshot: blue pill with white text inside
  for (const n of nodes) {
    const label = (n.id || '').toString().split(/\s+/)[0];
    drawPillNode(ctx, n.x, n.y, label);
  }
}

// Draw a rounded pill with text centered (matches screenshot styling)
function drawPillNode(ctx, cx, cy, text) {
  ctx.save();
  const font = '13px sans-serif';
  ctx.font = font;
  const padX = 14, height = 26, r = height/2;
  const m = ctx.measureText(text);
  const width = Math.max(34, Math.ceil(m.width) + padX*2);
  const x = cx - width/2, y = cy - height/2;

  // rounded rect
  roundRect(ctx, x, y, width, height, r);
  ctx.fillStyle = '#2563eb';
  ctx.fill();
  ctx.lineWidth = 3;
  ctx.strokeStyle = '#ffffff';
  ctx.stroke();

  // text
  ctx.fillStyle = '#ffffff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, cx, cy);
  ctx.restore();
}

// Helper: draw rounded rectangle path
function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, h/2, w/2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.lineTo(x + w - rr, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + rr);
  ctx.lineTo(x + w, y + h - rr);
  ctx.quadraticCurveTo(x + w, y + h, x + w - rr, y + h);
  ctx.lineTo(x + rr, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - rr);
  ctx.lineTo(x, y + rr);
  ctx.quadraticCurveTo(x, y, x + rr, y);
}

// convert string to pastel color for node
function stringToColor(s) {
  let h = 0;
  for (let i=0;i<s.length;i++) h = s.charCodeAt(i) + ((h<<5)-h);
  const hue = Math.abs(h) % 360;
  return `hsl(${hue} 70% 40%)`;
}

// debounce helper
function debounce(fn, wait) {
  let t;
  return function(...args) { clearTimeout(t); t = setTimeout(()=>fn.apply(this,args), wait); };
}

window.addEventListener('resize', debounce(() => { try { loadAndRenderNetwork(); } catch(e){} }, 200));

async function exportJson() {
  try {
    const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
    const txEndpoint = isAdmin ? '/api/transactions' : '/api/transactions/me';
    const resp = await fetch(txEndpoint);
    const json = await resp.json();
    const blob = new Blob([JSON.stringify(json, null, 2)], {type:'application/json'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'transactions.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (err) { showToast('Export failed: ' + err.message, true); }
}

function showToast(msg, isError) {
  const t = document.getElementById('resultToast');
  t.style.display = 'block';
  t.textContent = msg;
  t.className = isError ? 'toast error' : 'toast';
  setTimeout(()=> t.style.display='none', 6000);
}

// wire buttons
window.addEventListener('load', async () => {
  const isAdmin = document.querySelector('meta[name="is_admin"]')?.getAttribute('content') === 'true';
  // Verify server auth matches the page meta; reload if mismatch
  try {
    const who = await fetchWhoami();
    const metaUser = document.querySelector('meta[name="current_user"]')?.getAttribute('content') || '';
    if (who && (who.username || '') !== (metaUser || '')) {
      console.debug('[DEBUG] current_user meta mismatch', { metaUser, who });
      // attempt a reload to refresh server-rendered meta and CSRF token
      setTimeout(() => window.location.reload(), 200);
      return; // wait for reload
    }
  } catch (e) { /* ignore */ }
  // default to raw network view showing current transactions
  setNetworkMode('raw');
  await loadTransactions();
  await loadBalancesAndRender();
  await loadPersonalNotifications();
  const optimizeBtn = document.getElementById('optimizeBtn');
  if (optimizeBtn) {
    if (!isAdmin) optimizeBtn.textContent = 'Review Settlements';
    optimizeBtn.addEventListener('click', () => optimize(isAdmin));
  }
  document.getElementById('exportBtn').addEventListener('click', exportJson);
  // history is now on a dedicated page (/history)
  // wire add transaction UI
  document.getElementById('showAddForm').addEventListener('click', () => {
    document.getElementById('addTxForm').style.display = 'block';
  });
  document.getElementById('cancelAdd').addEventListener('click', () => {
    document.getElementById('addTxForm').reset();
    document.getElementById('addTxForm').style.display = 'none';
  });
  document.getElementById('addTxForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    // default payer to the current authenticated user if left blank
    const currentUser = document.querySelector('meta[name="current_user"]')?.getAttribute('content') || '';
    let payer = document.getElementById('payerUsername').value.trim();
    const payee = document.getElementById('payeeUsername').value.trim();
    if (!payer) payer = currentUser;
    const amount = parseFloat(document.getElementById('amount').value) || 0;
    const description = document.getElementById('description').value.trim();
    const body = { payerUsername: payer, payeeUsername: payee, amount: amount, description: description };
    try {
      const csrf = getCsrf();
      const saved = await fetchJson('/api/transactions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.header]: csrf.token },
        body: JSON.stringify(body)
      });
      console.debug('[DEBUG] transaction saved response', saved);
      // If server returned a saved object but payer/payee appear missing or blank,
      // force a full reload to ensure page meta/CSRF are fresh and the subsequent
      // fetch for /api/transactions/me will return expected results.
      if (!saved || !saved.payerUsername || !saved.payeeUsername) {
        showToast('Transaction saved but response unexpected, refreshing…');
        setTimeout(()=> window.location.reload(), 400);
        return;
      }
    // Wait for the new tx to appear in /api/transactions/me (in case of tiny commit latency)
    const appeared = await waitForTxPresence({ isAdmin, txId: saved?.id });
    if (!appeared) console.debug('[DEBUG] saved tx not visible yet in /me list; proceeding with refresh anyway');
    showToast('Transaction added');
      document.getElementById('addTxForm').reset();
      document.getElementById('addTxForm').style.display = 'none';
  setNetworkMode('raw'); // after any change, show current transactions network
    // Single-source refresh
    const txsAfter = await loadTransactions();
    console.debug('[DEBUG] post-add loadTransactions count', txsAfter?.length || 0);
      await loadBalancesAndRender();
      // Do not auto-show settlement instructions here; show only on explicit Optimize click
      // refresh network visualization
      await loadAndRenderNetwork();
    } catch (err) {
      showToast('Add transaction failed: ' + err.message, true);
      console.error(err);
    }
  });
  // initial network render
  await loadAndRenderNetwork();
  // poll personal notifications every 60 seconds
  setInterval(loadPersonalNotifications, 60000);
});

// history handled on separate /history page
