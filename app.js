const DEFAULT_SYMBOLS = 'USDT, BTC, GIGGLE, SOL, ETH, DOT, BNB, DOGE, XRP, FORM';
const DEFAULT_THRESHOLD = 2.5;
const DEFAULT_THRESHOLDS = {
  1: 0.5,
  5: 1.0,
  15: 1.5,
  60: 2.5,
  1440: 4.0,
  10080: 6.0,
  43200: 10.0,
};
const WINDOW_MS = {
  1: 60 * 1000,
  5: 5 * 60 * 1000,
  15: 15 * 60 * 1000,
  60: 60 * 60 * 1000,
  1440: 24 * 60 * 60 * 1000,
  10080: 7 * 24 * 60 * 60 * 1000,
  43200: 30 * 24 * 60 * 60 * 1000,
};
const FAST_WINDOW_LIMIT = 60;
const FAST_RECORD_MS = 1000;
const SLOW_RECORD_MS = 60 * 1000;
const KNOWN_QUOTES = ['USDT', 'BTC', 'ETH', 'BNB', 'BUSD', 'FDUSD', 'USDC', 'EUR', 'GBP', 'TRY'];

const dom = {
  symbols: document.getElementById('symbols'),
  quote: document.getElementById('quote'),
  windows: document.querySelectorAll('input[data-window]'),
  windowThresholds: document.querySelectorAll('input[data-window-threshold]'),
  cooldown: document.getElementById('cooldown'),
  start: document.getElementById('start'),
  stop: document.getElementById('stop'),
  clear: document.getElementById('clear-alerts'),
  sound: document.getElementById('sound'),
  statusPill: document.getElementById('status-pill'),
  statusText: document.getElementById('status-text'),
  statusNote: document.getElementById('status-note'),
  tableBody: document.getElementById('table-body'),
  alerts: document.getElementById('alerts'),
  alertsCount: document.getElementById('alerts-count'),
  symbolCount: document.getElementById('symbol-count'),
  lastUpdate: document.getElementById('last-update'),
  toggleLiveExpand: document.getElementById('toggle-live-expand'),
};

dom.symbols.value = DEFAULT_SYMBOLS;
const livePanel = document.querySelector('.panel.live');

const state = {
  running: false,
  socket: null,
  symbols: [],
  symbolState: new Map(),
  windows: [1, 5, 15],
  thresholds: { ...DEFAULT_THRESHOLDS },
  maxFastWindowMs: WINDOW_MS[15],
  maxSlowWindowMs: 0,
  cooldownMs: 30000,
  updateTimer: null,
  reconnectTimer: null,
  lastMessageAt: null,
  audioCtx: null,
  alertCount: 0,
};

function setStatus(mode, text) {
  dom.statusPill.className = `status-pill ${mode}`;
  dom.statusPill.textContent = mode === 'live' ? 'Live' : mode === 'warn' ? 'Alert' : 'Idle';
  dom.statusText.textContent = text;
}

function setNote(text) {
  dom.statusNote.textContent = text;
}

function getSelectedWindows() {
  return Array.from(dom.windows)
    .filter((input) => input.checked)
    .map((input) => Number(input.dataset.window));
}

function formatWindowLabel(windowMinutes) {
  if (windowMinutes === 60) {
    return '1h';
  }
  if (windowMinutes === 1440) {
    return '1d';
  }
  if (windowMinutes === 10080) {
    return '1w';
  }
  if (windowMinutes === 43200) {
    return '1mo';
  }
  return `${windowMinutes}m`;
}

function getWindowThresholds(windows) {
  const thresholds = {};
  const errors = [];
  windows.forEach((windowMinutes) => {
    const input = document.querySelector(`input[data-window-threshold="${windowMinutes}"]`);
    const raw = input ? input.value : DEFAULT_THRESHOLDS[windowMinutes];
    const value = Number(raw);
    if (!Number.isFinite(value) || value <= 0) {
      errors.push(formatWindowLabel(windowMinutes));
      return;
    }
    thresholds[windowMinutes] = value;
  });
  return { thresholds, errors };
}

function parseSymbols(input, quote) {
  const tokens = input.split(/[\s,]+/).map((token) => token.trim()).filter(Boolean);
  const symbols = [];
  const ignored = [];
  const seen = new Set();

  tokens.forEach((token) => {
    let cleaned = token.toUpperCase();
    cleaned = cleaned.replace(/[^A-Z0-9:/-]/g, '');
    if (!cleaned) {
      return;
    }

    if (cleaned === quote) {
      ignored.push(cleaned);
      return;
    }

    let pair = cleaned;
    if (cleaned.includes('/') || cleaned.includes('-') || cleaned.includes(':')) {
      pair = cleaned.replace(/[^A-Z0-9]/g, '');
    } else {
      const matchedQuote = KNOWN_QUOTES.find((q) => cleaned.endsWith(q) && cleaned.length > q.length);
      if (matchedQuote) {
        pair = cleaned;
      } else {
        pair = `${cleaned}${quote}`;
      }
    }

    if (pair === quote) {
      ignored.push(cleaned);
      return;
    }

    if (!seen.has(pair)) {
      seen.add(pair);
      symbols.push(pair);
    }
  });

  return { symbols, ignored };
}

function applyWindowVisibility(windows) {
  const windowSet = new Set(windows.map(String));
  document.querySelectorAll('[data-window-col]').forEach((cell) => {
    const windowId = cell.dataset.windowCol;
    cell.style.display = windowSet.has(windowId) ? '' : 'none';
  });
  document.querySelectorAll('[data-window-cell]').forEach((cell) => {
    const windowId = cell.dataset.windowCell;
    cell.style.display = windowSet.has(windowId) ? '' : 'none';
  });
}

function buildTable(symbols) {
  dom.tableBody.innerHTML = '';
  symbols.forEach((symbol) => {
    const row = document.createElement('tr');
    row.dataset.symbol = symbol;
    row.innerHTML = `
      <td class="symbol">${symbol}</td>
      <td class="price" data-field="price">--</td>
      <td class="drop" data-window-cell="1">--</td>
      <td class="drop" data-window-cell="5">--</td>
      <td class="drop" data-window-cell="15">--</td>
      <td class="drop" data-window-cell="60">--</td>
      <td class="drop" data-window-cell="1440">--</td>
      <td class="drop" data-window-cell="10080">--</td>
      <td class="drop" data-window-cell="43200">--</td>
      <td class="time" data-field="time">--</td>
    `;
    dom.tableBody.appendChild(row);
  });
}

function initSymbolState(symbols) {
  state.symbolState.clear();
  symbols.forEach((symbol) => {
    const changes = {};
    const stepState = {};
    state.windows.forEach((windowLabel) => {
      changes[windowLabel] = null;
      stepState[windowLabel] = { up: 0, down: 0 };
    });
    state.symbolState.set(symbol, {
      pointsFast: [],
      pointsSlow: [],
      lastPrice: null,
      lastTickAt: null,
      lastFastRecordAt: 0,
      lastSlowRecordAt: 0,
      changes,
      lastAlertAt: {},
      lastAlertStep: stepState,
    });
  });
}

function formatPrice(value) {
  if (value === null || Number.isNaN(value)) {
    return '--';
  }
  if (value >= 1000) {
    return value.toFixed(2);
  }
  if (value >= 1) {
    return value.toFixed(4);
  }
  return value.toFixed(6);
}

function formatPercent(value) {
  if (value === null || Number.isNaN(value)) {
    return '--';
  }
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}

function updateLastUpdate() {
  if (!state.lastMessageAt) {
    dom.lastUpdate.textContent = 'No updates yet';
    return;
  }
  const seconds = Math.floor((Date.now() - state.lastMessageAt) / 1000);
  dom.lastUpdate.textContent = `Last tick ${seconds}s ago`;
}

function pushAlert({ symbol, windowLabel, changePct, threshold, price, direction }) {
  const card = document.createElement('div');
  const levelClass = direction === 'up' ? 'good' : 'danger';
  const time = new Date().toLocaleTimeString();
  card.className = `alert-card ${levelClass}`;
  card.innerHTML = `
    <div class="alert-title">${symbol} moved ${formatPercent(changePct)} in ${windowLabel}</div>
    <div class="alert-meta">Price $${formatPrice(price)} | Threshold ${threshold.toFixed(2)}% | ${time}</div>
  `;
  dom.alerts.prepend(card);
  state.alertCount += 1;
  dom.alertsCount.textContent = state.alertCount;

  while (dom.alerts.children.length > 20) {
    dom.alerts.removeChild(dom.alerts.lastChild);
  }
}

function playBeep() {
  if (!dom.sound.checked) {
    return;
  }
  if (!window.AudioContext) {
    return;
  }
  if (!state.audioCtx) {
    state.audioCtx = new AudioContext();
  }
  const ctx = state.audioCtx;
  const oscillator = ctx.createOscillator();
  const gain = ctx.createGain();
  oscillator.type = 'sine';
  oscillator.frequency.value = 740;
  gain.gain.value = 0.08;
  oscillator.connect(gain);
  gain.connect(ctx.destination);
  oscillator.start();
  oscillator.stop(ctx.currentTime + 0.12);
}

function maybeAlert(symbol, windowLabel, direction, changePct, threshold, price) {
  const record = state.symbolState.get(symbol);
  if (!record) {
    return false;
  }
  const key = `${windowLabel}-${direction}`;
  const now = Date.now();
  if (record.lastAlertAt[key] && now - record.lastAlertAt[key] < state.cooldownMs) {
    return false;
  }
  record.lastAlertAt[key] = now;
  setStatus('warn', `${symbol} moved ${formatPercent(changePct)} in ${windowLabel}`);
  pushAlert({ symbol, windowLabel, changePct, threshold, price, direction });
  playBeep();
  return true;
}

function triggerPulse(row) {
  if (!row) {
    return;
  }
  row.classList.remove('pulse');
  void row.offsetWidth;
  row.classList.add('pulse');
  if (row.pulseTimer) {
    clearTimeout(row.pulseTimer);
  }
  row.pulseTimer = setTimeout(() => row.classList.remove('pulse'), 1800);
}

function computeChange(points, now, windowMs, currentPrice) {
  if (currentPrice === null || currentPrice === undefined) {
    return null;
  }
  let max = currentPrice;
  let min = currentPrice;
  for (let i = points.length - 1; i >= 0; i -= 1) {
    const point = points[i];
    if (now - point.t > windowMs) {
      break;
    }
    if (point.p > max) {
      max = point.p;
    }
    if (point.p < min) {
      min = point.p;
    }
  }
  if (max <= 0 || min <= 0) {
    return { strongest: 0, up: 0, down: 0 };
  }
  const down = ((currentPrice - max) / max) * 100;
  const up = ((currentPrice - min) / min) * 100;
  const strongest = Math.abs(up) >= Math.abs(down) ? up : down;
  return { strongest, up, down };
}

function updateTable() {
  const now = Date.now();
  updateLastUpdate();

  state.symbols.forEach((symbol) => {
    const record = state.symbolState.get(symbol);
    if (!record) {
      return;
    }
    const row = dom.tableBody.querySelector(`tr[data-symbol="${symbol}"]`);
    if (!row) {
      return;
    }

    const priceCell = row.querySelector('[data-field="price"]');
    const timeCell = row.querySelector('[data-field="time"]');
    priceCell.textContent = record.lastPrice ? formatPrice(record.lastPrice) : '--';
    timeCell.textContent = record.lastTickAt ? new Date(record.lastTickAt).toLocaleTimeString() : '--';

    state.windows.forEach((windowLabel) => {
      const points = windowLabel > FAST_WINDOW_LIMIT ? record.pointsSlow : record.pointsFast;
      const change = computeChange(points, now, WINDOW_MS[windowLabel], record.lastPrice);
      const changePct = change ? change.strongest : null;
      record.changes[windowLabel] = changePct;
      const dropCell = row.querySelector(`[data-window-cell="${windowLabel}"]`);
      if (!dropCell) {
        return;
      }
      const threshold = state.thresholds[windowLabel];
      if (!threshold) {
        dropCell.textContent = '--';
        return;
      }
      dropCell.textContent = formatPercent(changePct);
      dropCell.classList.remove('warn', 'danger', 'good');
      if (changePct === null) {
        return;
      }
      if (changePct >= threshold) {
        dropCell.classList.add('good');
      } else if (changePct <= -threshold) {
        dropCell.classList.add('danger');
      } else if (changePct < 0) {
        dropCell.classList.add('warn');
      } else if (changePct > 0) {
        dropCell.classList.add('good');
      }

      if (change) {
        const stepState = record.lastAlertStep[windowLabel] || { up: 0, down: 0 };
        record.lastAlertStep[windowLabel] = stepState;

        const upStep = change.up >= threshold ? Math.floor(change.up / threshold) : 0;
        const downStep = change.down <= -threshold ? Math.floor(Math.abs(change.down) / threshold) : 0;

        if (upStep === 0) {
          stepState.up = 0;
        }
        if (downStep === 0) {
          stepState.down = 0;
        }

        let direction = null;
        let changeValue = null;
        let step = 0;
        if (upStep > 0 && downStep > 0) {
          if (Math.abs(change.up) >= Math.abs(change.down)) {
            direction = 'up';
            changeValue = change.up;
            step = upStep;
          } else {
            direction = 'down';
            changeValue = change.down;
            step = downStep;
          }
        } else if (upStep > 0) {
          direction = 'up';
          changeValue = change.up;
          step = upStep;
        } else if (downStep > 0) {
          direction = 'down';
          changeValue = change.down;
          step = downStep;
        }

        if (direction && step > stepState[direction]) {
          const alerted = maybeAlert(symbol, formatWindowLabel(windowLabel), direction, changeValue, threshold, record.lastPrice);
          if (alerted) {
            stepState[direction] = step;
            triggerPulse(row);
          }
        }
      }
    });
  });
}

function updateRecord(symbol, price) {
  const record = state.symbolState.get(symbol);
  if (!record) {
    return;
  }
  const now = Date.now();
  record.lastPrice = price;
  record.lastTickAt = now;

  if (state.maxFastWindowMs > 0 && now - record.lastFastRecordAt >= FAST_RECORD_MS) {
    record.pointsFast.push({ t: now, p: price });
    record.lastFastRecordAt = now;
    const cutoff = now - state.maxFastWindowMs;
    while (record.pointsFast.length > 0 && record.pointsFast[0].t < cutoff) {
      record.pointsFast.shift();
    }
  }

  if (state.maxSlowWindowMs > 0 && now - record.lastSlowRecordAt >= SLOW_RECORD_MS) {
    record.pointsSlow.push({ t: now, p: price });
    record.lastSlowRecordAt = now;
    const cutoff = now - state.maxSlowWindowMs;
    while (record.pointsSlow.length > 0 && record.pointsSlow[0].t < cutoff) {
      record.pointsSlow.shift();
    }
  }
}

function setLiveExpanded(expanded) {
  if (!dom.toggleLiveExpand || !livePanel) {
    return;
  }
  livePanel.classList.toggle('expanded', expanded);
  document.body.classList.toggle('live-expanded', expanded);
  dom.toggleLiveExpand.textContent = expanded ? 'Exit full screen' : 'Expand';
  dom.toggleLiveExpand.setAttribute('aria-pressed', expanded ? 'true' : 'false');
}

function connectSocket(symbols) {
  if (state.socket) {
    state.socket.close();
  }
  const streams = symbols.map((symbol) => `${symbol.toLowerCase()}@trade`).join('/');
  const url = `wss://stream.binance.com:9443/stream?streams=${streams}`;
  const socket = new WebSocket(url);
  state.socket = socket;

  socket.onopen = () => {
    setStatus('live', 'Streaming prices from Binance');
    setNote('');
  };

  socket.onmessage = (event) => {
    state.lastMessageAt = Date.now();
    const payload = JSON.parse(event.data);
    if (!payload.data || !payload.data.s || !payload.data.p) {
      return;
    }
    const symbol = payload.data.s;
    const price = Number(payload.data.p);
    updateRecord(symbol, price);
  };

  socket.onerror = () => {
    setStatus('warn', 'Connection error. Attempting to recover...');
  };

  socket.onclose = () => {
    if (!state.running) {
      return;
    }
    setStatus('warn', 'Disconnected. Reconnecting soon...');
    state.reconnectTimer = setTimeout(() => connectSocket(state.symbols), 3000);
  };
}

function startMonitor() {
  const quote = dom.quote.value.trim().toUpperCase();
  const { symbols, ignored } = parseSymbols(dom.symbols.value, quote);

  state.windows = getSelectedWindows();
  const { thresholds, errors } = getWindowThresholds(state.windows);
  state.thresholds = thresholds;
  state.cooldownMs = Number(dom.cooldown.value) * 1000;

  if (symbols.length === 0) {
    setStatus('warn', 'Add at least one pair to watch.');
    return;
  }
  if (state.windows.length === 0) {
    setStatus('warn', 'Select at least one time window.');
    return;
  }
  const fastWindows = state.windows.filter((windowLabel) => windowLabel <= FAST_WINDOW_LIMIT);
  const slowWindows = state.windows.filter((windowLabel) => windowLabel > FAST_WINDOW_LIMIT);
  state.maxFastWindowMs = fastWindows.length
    ? Math.max(...fastWindows.map((windowLabel) => WINDOW_MS[windowLabel] || 0))
    : 0;
  state.maxSlowWindowMs = slowWindows.length
    ? Math.max(...slowWindows.map((windowLabel) => WINDOW_MS[windowLabel] || 0))
    : 0;
  if (errors.length > 0) {
    setStatus('warn', `Enter valid thresholds for: ${errors.join(', ')}`);
    return;
  }

  if (dom.sound.checked && !state.audioCtx && window.AudioContext) {
    state.audioCtx = new AudioContext();
  }
  if (state.audioCtx && state.audioCtx.state === 'suspended') {
    state.audioCtx.resume();
  }

  if (ignored.length > 0) {
    setNote(`Ignored: ${ignored.join(', ')}`);
  } else {
    setNote('');
  }

  state.running = true;
  state.symbols = symbols;
  dom.symbolCount.textContent = symbols.length;
  dom.start.disabled = true;
  dom.stop.disabled = false;

  buildTable(symbols);
  applyWindowVisibility(state.windows);
  initSymbolState(symbols);

  state.alertCount = 0;
  dom.alertsCount.textContent = '0';
  dom.alerts.innerHTML = '';

  connectSocket(symbols);
  if (state.updateTimer) {
    clearInterval(state.updateTimer);
  }
  state.updateTimer = setInterval(updateTable, 1000);
  setStatus('live', 'Streaming prices from Binance');
}

function stopMonitor() {
  state.running = false;
  if (state.socket) {
    state.socket.close();
    state.socket = null;
  }
  if (state.updateTimer) {
    clearInterval(state.updateTimer);
    state.updateTimer = null;
  }
  if (state.reconnectTimer) {
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
  }
  dom.start.disabled = false;
  dom.stop.disabled = true;
  setStatus('idle', 'Monitoring stopped');
}

function bindEvents() {
  dom.start.addEventListener('click', () => {
    startMonitor();
  });

  dom.stop.addEventListener('click', () => {
    stopMonitor();
  });

  dom.clear.addEventListener('click', () => {
    dom.alerts.innerHTML = '';
    state.alertCount = 0;
    dom.alertsCount.textContent = '0';
  });

  if (dom.toggleLiveExpand && livePanel) {
    dom.toggleLiveExpand.addEventListener('click', () => {
      setLiveExpanded(!livePanel.classList.contains('expanded'));
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && livePanel.classList.contains('expanded')) {
        setLiveExpanded(false);
      }
    });
  }
}

bindEvents();
