async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

function formatMoney(amount) { return amount==null? '0.00' : Number(amount).toFixed(2); }

function renderEntry(e) {
  const div = document.createElement('div');
  div.className = 'history-card';
  let payload = e.payload;
  let recordedAt = e.timestamp;
  let originalTs = null;
  try {
    const p = JSON.parse(e.payload);
    payload = `${p.payerUsername} → ${p.payeeUsername} : ${Number(p.amount).toFixed(2)}${p.description?` (${p.description})`:''}`;
    if (p.recordedAt) recordedAt = p.recordedAt;
    if (p.timestamp && p.timestamp !== 'null') originalTs = p.timestamp;
  } catch(_) {}

  const timeHtml = originalTs ? `${new Date(recordedAt).toLocaleString()} <small class="muted">(tx: ${new Date(originalTs).toLocaleString()})</small>` : new Date(recordedAt).toLocaleString();

  div.innerHTML = `
    <div class="history-top">
      <div class="history-time">${timeHtml}</div>
      <div class="history-action"><strong>${e.action}</strong></div>
    </div>
    <div class="history-body">
      <div class="history-payload">${payload}</div>
      <div class="history-meta muted">by ${e.performedBy || 'system'}</div>
    </div>
  `;
  return div;
}

async function loadHistory() {
  const container = document.getElementById('historyContainer');
  try {
    const entries = await fetchJson('/api/transactions/history');
    container.innerHTML = '';
    if (!entries || entries.length === 0) {
      container.innerHTML = '<div class="empty">No history yet</div>';
      return;
    }
    entries.forEach(e => container.appendChild(renderEntry(e)));
  } catch (err) {
    container.innerHTML = `<div class="error">Failed to load history: ${err.message}</div>`;
    console.error(err);
  }
}

window.addEventListener('load', async () => {
  await loadHistory();
  document.getElementById('refreshHistory').addEventListener('click', loadHistory);
  document.getElementById('exportHistory').addEventListener('click', async () => {
    try {
      const entries = await fetchJson('/api/transactions/history');
      const blob = new Blob([JSON.stringify(entries, null, 2)], {type:'application/json'});
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a'); a.href = url; a.download = 'transaction_history.json'; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
    } catch (err) { alert('Export failed: ' + err.message); }
  });
});