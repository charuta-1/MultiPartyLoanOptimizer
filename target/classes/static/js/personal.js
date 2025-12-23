async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

function formatMoney(amount) {
  if (amount == null) return '0';
  return Number(amount).toFixed(2);
}
function formatINR(amount) {
  try { return '₹' + (Number(amount || 0)).toFixed(2); } catch { return '₹0.00'; }
}

function showToast(msg, isError) {
  const t = document.getElementById('resultToast');
  if (!t) { alert(msg); return; }
  t.style.display = 'block';
  t.textContent = msg;
  t.className = isError ? 'toast error' : 'toast';
  setTimeout(()=> t.style.display='none', 4000);
}

function normalizeDigits(input) {
  if (!input) return '';
  return input.replace(/[^0-9]/g, '');
}

function openUpiLink(uri) {
  try {
    window.location.href = uri;
  } catch (_) {
    const link = document.createElement('a');
    link.href = uri;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }
}

function formatDateLabel(dateString) {
  if (!dateString) return 'Unknown date';
  try {
    const [date] = dateString.split('T');
    const formatted = new Date(dateString).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    return formatted || date;
  } catch (_) {
    return dateString || 'Unknown date';
  }
}

// Load personal settlements grouped by created date with status badges for settled/pending
async function loadPersonal() {
  const container = document.getElementById('personalList');
  try {
    const [notifications, entries] = await Promise.all([
      fetchJson('/api/personal-notifications').catch(() => ({ owe: [], receive: [] })),
      fetchJson('/api/personal-entries').catch(() => [])
    ]);
  const pending = notifications?.owe || [];
  const awaitingReceive = notifications?.receive || [];
  const currentUser = (document.querySelector('meta[name="current_user"]')?.content || '').trim().toLowerCase();
    container.innerHTML = '';

    // Pending section (unsettled where current user is payer)
    const pendingBlock = document.createElement('div');
    pendingBlock.className = 'personal-day-block';
    const pHead = document.createElement('h2');
    pHead.className = 'personal-day-heading';
    pHead.textContent = `You need to settle${pending.length ? ` (${pending.length})` : ''}`;
    pendingBlock.appendChild(pHead);
    const pList = document.createElement('ul');
    pList.className = 'personal-day-list';
    if (pending && pending.length) {
      pending.forEach(en => {
        const li = document.createElement('li');
        li.className = 'personal-entry';
        const left = document.createElement('span');
        left.className = 'pe-main';
        const phoneLabel = en.toUserPhone ? ` (${en.toUserPhone})` : '';
        left.innerHTML = `<span class="settled-badge settled-badge-warning">Pending</span><span>You owe ${formatINR(en.amount)} to ${en.toUser}${phoneLabel}</span>`;
        li.appendChild(left);
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn-mini btn-pay';
        btn.textContent = 'Pay';
        btn.addEventListener('click', async () => {
          const rawPhone = en.toUserPhone || '';
          const normalizedDigits = normalizeDigits(rawPhone.startsWith('+') ? rawPhone.slice(1) : rawPhone);
          if (!normalizedDigits) {
            showToast(`Phone number for ${en.toUser} is missing. Ask them to update their profile.`, true);
            return;
          }
          const amountValue = Number(en.amount || 0).toFixed(2);
          const payeeName = encodeURIComponent(en.toUser || 'SmartSplit contact');
          const note = encodeURIComponent(`SmartSplit settlement from ${en.fromUser || 'member'}`);
          const upiId = `${normalizedDigits}@upi`;
          const upiUri = `upi://pay?pa=${encodeURIComponent(upiId)}&pn=${payeeName}&am=${amountValue}&cu=INR&tn=${note}`;

          showToast('Opening your UPI app…', false);
          openUpiLink(upiUri);

          setTimeout(async () => {
            try {
              // If the notification has a valid id (not null, not undefined, not "null"),
              // call the direct id-based settle endpoint.
              // For optimized notifications (which may be synthetic and have no id),
              // POST a JSON payload with fromUser/toUser/amount to the fallback endpoint.
              let resp;
              if (en.id && en.id !== 'null' && en.id !== null && en.id !== undefined) {
                resp = await fetch(`/api/personal/${en.id}/settle`, { method: 'POST' });
              } else {
                const body = JSON.stringify({ fromUser: en.fromUser, toUser: en.toUser, amount: en.amount });
                resp = await fetch('/api/personal/settle', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
              }
              const json = await resp.json();
              if (json.status !== 'ok') throw new Error('Server rejected');
              showToast(`Marked settled with ${en.toUser}.`, false);
              await loadPersonal();
            } catch (err) {
              showToast('Marked settled failed: ' + err.message, true);
            }
          }, 1500);
        });
        li.appendChild(btn);
        pList.appendChild(li);
      });
    } else {
      const li = document.createElement('li');
      li.className = 'personal-entry';
      li.innerHTML = '<span class="pe-main">No pending settlements</span>';
      pList.appendChild(li);
    }
    pendingBlock.appendChild(pList);
    container.appendChild(pendingBlock);

    // Receiving section (unsettled where current user should receive money)
    const receiveBlock = document.createElement('div');
    receiveBlock.className = 'personal-day-block';
    const rHead = document.createElement('h2');
    rHead.className = 'personal-day-heading';
    rHead.textContent = `Waiting to receive${awaitingReceive.length ? ` (${awaitingReceive.length})` : ''}`;
    receiveBlock.appendChild(rHead);
    const rList = document.createElement('ul');
    rList.className = 'personal-day-list';
    if (awaitingReceive && awaitingReceive.length) {
      awaitingReceive.forEach(en => {
        const li = document.createElement('li');
        li.className = 'personal-entry';
        const phoneLabel = en.fromUserPhone ? ` (${en.fromUserPhone})` : '';
        li.innerHTML = `<span class="pe-main"><span class="settled-badge settled-badge-warning">Pending</span><span>${en.fromUser} owes you ${formatINR(en.amount)}${phoneLabel}</span></span>`;
        rList.appendChild(li);
      });
    } else {
      const li = document.createElement('li');
      li.className = 'personal-entry';
      li.innerHTML = '<span class="pe-main">No amounts awaiting receipt</span>';
      rList.appendChild(li);
    }
    receiveBlock.appendChild(rList);
    container.appendChild(receiveBlock);

    const recentSettled = (entries || []).filter(en => {
      if (!en || !en.settled || !en.toUser || !en.settledAt) return false;
      if (!currentUser) return false;
      return en.toUser.toLowerCase() === currentUser;
    }).sort((a, b) => (b.settledAt || '').localeCompare(a.settledAt || '')).slice(0, 3);

    if (recentSettled.length) {
      const settledBlock = document.createElement('div');
      settledBlock.className = 'personal-day-block';
      const sHead = document.createElement('h2');
      sHead.className = 'personal-day-heading';
      sHead.textContent = 'Recently settled';
      settledBlock.appendChild(sHead);
      const sList = document.createElement('ul');
      sList.className = 'personal-day-list';
      recentSettled.forEach(en => {
        const li = document.createElement('li');
        li.className = 'personal-entry';
        const settledTime = new Date(en.settledAt).toLocaleString([], { hour: '2-digit', minute: '2-digit' });
        const payer = en.fromUser || 'Someone';
        li.innerHTML = `<span class="pe-main"><span class="settled-badge">Settled</span><span>${payer} paid ${formatINR(en.amount)} • ${settledTime}</span></span>`;
        sList.appendChild(li);
      });
      settledBlock.appendChild(sList);
      container.appendChild(settledBlock);
    }

    if (!entries || !entries.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No personal settlements recorded yet';
      container.appendChild(empty);
      return;
    }

    // group entries by created date for timeline display
    const dayMap = new Map();
    entries.forEach(en => {
      const created = en.createdAt || '';
      const dateKey = created.includes('T') ? created.split('T')[0] : created;
      if (!dayMap.has(dateKey)) dayMap.set(dateKey, []);
      dayMap.get(dateKey).push(en);
    });

    const sortedDates = Array.from(dayMap.keys()).sort((a,b) => b.localeCompare(a));
    sortedDates.forEach(dateKey => {
      const block = document.createElement('div');
      block.className = 'personal-day-block';
      const heading = document.createElement('h2');
      heading.className = 'personal-day-heading';
      heading.textContent = formatDateLabel(dateKey);
      block.appendChild(heading);

      const ul = document.createElement('ul');
      ul.className = 'personal-day-list';
      dayMap.get(dateKey).forEach(en => {
        const li = document.createElement('li');
        li.className = 'personal-entry';
        const badgeClass = en.settled ? 'settled-badge' : 'settled-badge settled-badge-warning';
        const settledByLabel = en.settled && en.settledBy ? ` by ${en.settledBy}` : '';
        const badgeLabel = en.settled ? `Settled${settledByLabel}` : 'Pending';
        const direction = `${en.fromUser} → ${en.toUser}`;
        const createdTime = en.createdAt ? new Date(en.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
        const settledTime = en.settled && en.settledAt ? new Date(en.settledAt).toLocaleString([], { hour: '2-digit', minute: '2-digit' }) : '';
        const meta = en.settled ? (settledTime ? ` • Settled ${settledTime}` : '') : (createdTime ? ` • Recorded ${createdTime}` : '');
        li.innerHTML = `<span class="pe-main"><span class="${badgeClass}">${badgeLabel}</span><span>${direction}: ${formatINR(en.amount)}${meta}</span></span>`;
        ul.appendChild(li);
      });
      block.appendChild(ul);
      container.appendChild(block);
    });
  } catch (err) {
    container.innerHTML = `<div class="error">Failed to load personal snapshots: ${err.message}</div>`;
    console.error(err);
  }
}

// hook save button on index page if present
window.addEventListener('load', async () => {
  // if on personal page, load list
  if (document.getElementById('personalList')) {
    await loadPersonal();
  }
});