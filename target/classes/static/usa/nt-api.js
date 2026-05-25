/**
 * Northern Trust — live API. Sign in at /usa/login.html first.
 */
(function (global) {
  if (typeof global.NTAuth !== 'undefined' && !global.NTAuth.isLoggedIn() && !global.NT_ACCOUNT_NUMBER) {
    if (typeof window !== 'undefined' && !window.location.pathname.includes('login.html')) {
      window.location.replace('login.html');
    }
    return;
  }

  const API_BASE = global.NT_API_BASE || (global.location && global.location.protocol.startsWith('http')
    ? global.location.origin + '/api/v1'
    : 'http://localhost:8080/api/v1');
  const ACCOUNT = global.NT_ACCOUNT_NUMBER ||
    (typeof global.NTAuth !== 'undefined' ? global.NTAuth.getAccountNumber() : '') ||
    sessionStorage.getItem('nt_account_number') || '';

  async function apiGet(path, params) {
    const url = new URL(API_BASE + path);
    url.searchParams.set('accountNumber', ACCOUNT);
    if (params) Object.entries(params).forEach(([k, v]) => { if (v != null && v !== '') url.searchParams.set(k, v); });
    const res = await fetch(url.toString());
    if (!res.ok) throw new Error('HTTP ' + res.status + ': ' + (await res.text()));
    return res.json();
  }

  async function apiPost(path, body, params) {
    const url = new URL(API_BASE + path);
    url.searchParams.set('accountNumber', ACCOUNT);
    if (params) Object.entries(params).forEach(([k, v]) => { if (v != null) url.searchParams.set(k, v); });
    const res = await fetch(url.toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    if (!res.ok) throw new Error(await res.text());
    return res.json();
  }

  async function apiPatch(path, params) {
    const url = new URL(API_BASE + path);
    url.searchParams.set('accountNumber', ACCOUNT);
    if (params) Object.entries(params).forEach(([k, v]) => { if (v != null) url.searchParams.set(k, v); });
    const res = await fetch(url.toString(), { method: 'PATCH' });
    if (!res.ok) throw new Error(await res.text());
    return res.json();
  }

  const state = {
    overview: null,
    profile: null,
    balances: null,
    beneficiaries: null,
    transferHistory: null,
    statements: {},
    pendingApprovals: null,
    analytics: null,
    notifications: null,
    connected: false,
    ready: false
  };

  global.ntAccountBalances = {
    checking: 8245.30,
    savings: 35180.38,
    credit: 12500.00,
    invest: 17500.00
  };

  function fmtMoney(n) {
    const x = Number(n);
    const neg = x < 0;
    return (neg ? '-' : '') + '$' + Math.abs(x).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function formatNTDate(dateStr) {
    if (!dateStr) return '';
    if (dateStr.includes('-')) {
      const parts = dateStr.split('-');
      const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      const mIdx = parseInt(parts[1], 10) - 1;
      return `${months[mIdx]} ${parseInt(parts[2], 10)}`;
    }
    return dateStr.split(',')[0];
  }

  function mapStatements(list, tab) {
    if (!list || !list.length) return [];
    if (tab === 'checking') {
      return list.map(s => ({
        date: s.date, desc: s.description, amount: s.amount, balance: s.balanceAfter,
        channel: s.channel, type: s.type
      }));
    }
    if (tab === 'savings') {
      return list.map(s => ({
        date: s.date, type: s.type, amount: s.amount, rate: s.rate || '4.50%', balance: s.balanceAfter
      }));
    }
    if (tab === 'credit') {
      return list.map(s => ({
        date: s.date, type: s.type, charged: s.charged, payment: s.payment,
        balance: s.balanceAfter, credit: s.creditAvailable
      }));
    }
    if (tab === 'invest') {
      return list.map(s => ({
        date: s.date, asset: s.asset || s.description, type: s.type,
        units: s.units, price: s.price, value: s.amount, balance: s.balanceAfter
      }));
    }
    return list.map(s => ({
      date: s.date, desc: s.description, source: s.source, type: s.type,
      amount: s.amount, balance: s.balanceAfter, status: s.status, channel: s.channel
    }));
  }

  function applyGlobalMocks() {
    global.beneficiariesMock = mapBeneficiaries(state.beneficiaries);
    if (state.transferHistory && state.transferHistory.items) {
      global.masterTxLedger = state.transferHistory.items.map(tx => ({
        id: tx.id, date: tx.date, type: tx.type, counterparty: tx.counterparty,
        source: tx.source, amount: tx.amount, currency: tx.currency || 'USD', status: tx.status
      }));
    }
    if (state.pendingApprovals) {
      global.mockPendingApprovals = state.pendingApprovals.map(a => ({
        id: a.id, type: a.type, amount: a.amount, currency: a.currency,
        usdEquivalent: a.usdEquivalent, source: a.source, beneficiary: a.beneficiary,
        country: a.country, riskScore: a.riskScore, riskLevel: a.riskLevel,
        status: a.status, timestamp: a.timestamp,
        flags: a.flags || [], compliance: a.compliance || {}, history: a.history || []
      }));
    }
    global.unifiedMock = mapStatements(state.statements.unified, 'unified');
    global.checkingMock = mapStatements(state.statements.checking, 'checking');
    global.savingsMock = mapStatements(state.statements.savings, 'savings');
    global.creditMock = mapStatements(state.statements.credit, 'credit');
    global.investMock = mapStatements(state.statements.invest, 'invest');
    if (state.overview && state.overview.recentActivity) {
      const uniqueItems = [];
      const seen = new Set();
      state.overview.recentActivity.forEach(r => {
        const key = `${r.date}|${r.description}|${r.amount}`;
        if (!seen.has(key)) {
          seen.add(key);
          uniqueItems.push({
            date: r.date, desc: r.description, recipient: r.counterparty,
            amount: r.amount, type: r.type, typeClass: r.amount < 0 ? 'type-card' : 'type-wire'
          });
        }
      });
      global.transactionsMock = uniqueItems;
    }
    if (state.analytics) global.analyticsData = { kpis: state.analytics.kpis, connected: true };
  }

  function mapBeneficiaries(list) {
    return (list || []).map(b => ({
      beneficiaryId: b.beneficiaryId, type: b.type, displayName: b.displayName,
      relationship: b.relationship, destinationDetails: b.destinationDetails,
      transferLimits: b.transferLimits, status: b.status, isTrusted: b.isTrusted,
      createdAt: b.createdAt, lastUsedAt: b.lastUsedAt, trustLevel: b.trustLevel
    }));
  }

  function syncBalances() {
    if (!state.balances) return;
    Object.keys(state.balances).forEach(key => {
      const b = state.balances[key];
      global.ntAccountBalances[key] = Number(b.available != null ? b.available : b.balance);
    });
  }

  function hydrateSession() {
    const meta = document.getElementById('headerClientMeta');
    if (!meta) return;
    const p = state.profile;
    let login = 'May 24, 2026, 9:14 AM CT';
    if (p && p.lastLoginAt) {
      try {
        login = new Intl.DateTimeFormat('en-US', {
          dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Chicago'
        }).format(new Date(p.lastLoginAt)) + ' CT';
      } catch (e) { /* keep default */ }
    }
    const loc = (p && p.lastLoginLocation) || 'Chicago, IL';
    meta.innerHTML = 'Last sign-in: ' + login + ' · ' + loc +
      ' · <a href="#" id="reportSuspiciousLink" style="color:#3b82f6;text-decoration:none;font-weight:600;">Report suspicious activity</a>';
  }

  function hydrateHeader() {
    const p = state.profile || (state.overview && state.overview.profile) || (state.overview && state.overview.client);
    if (!p) {
      hydrateSession();
      return;
    }
    const name = p.displayName || (p.firstName + ' ' + p.lastName);
    const el = document.getElementById('headerClientName');
    if (el) el.textContent = name;
    hydrateSession();
  }

  function showConnectionBanner(msg) {
    const b = document.getElementById('ntConnectionBanner');
    const m = document.getElementById('ntConnectionBannerMsg');
    if (b && m) {
      m.textContent = msg || 'Unable to reach banking services. Showing cached demo data.';
      b.classList.add('visible');
    }
  }

  function hideConnectionBanner() {
    const b = document.getElementById('ntConnectionBanner');
    if (b) b.classList.remove('visible');
  }

  function hydrateOverview() {
    const o = state.overview;
    if (!o) return;
    const stats = document.querySelectorAll('.stat-card .stat-value');
    if (stats[0] && o.summary) stats[0].textContent = fmtMoney(o.summary.totalPortfolioBalance);
    if (stats[1] && o.summary) stats[1].textContent = fmtMoney(o.summary.availableBalance);
    if (stats[2] && o.summary) stats[2].textContent = fmtMoney(o.summary.pendingSettlements);
    const mom = document.querySelector('.badge-positive');
    if (mom && o.summary) mom.textContent = '+' + o.summary.monthOverMonthChangePct + '%';

    const cardMap = { checking: 'subCardChecking', savings: 'subCardSavings', credit: 'subCardCredit', invest: 'subCardInvest' };
    (o.accounts || []).forEach(acc => {
      const el = document.getElementById(cardMap[acc.key]);
      if (!el) return;
      const val = el.querySelector('.sub-account-value');
      if (val) {
        if (acc.key === 'invest') val.textContent = fmtMoney(acc.marketValue);
        else if (acc.key === 'credit') val.textContent = fmtMoney(acc.availableCredit);
        else val.textContent = fmtMoney(acc.balance);
      }
      const rows = el.querySelectorAll('.sub-account-info-row span');
      if (acc.key === 'checking' && rows.length >= 2) {
        if (acc.ledgerBalance != null) rows[0].textContent = 'Balance: ' + fmtMoney(acc.ledgerBalance);
        if (acc.pendingAmount != null) rows[1].textContent = 'Pending: ' + fmtMoney(acc.pendingAmount);
      }
      if (acc.key === 'savings' && rows.length >= 1 && acc.apyPct) {
        rows[0].textContent = 'APY: ' + acc.apyPct + '% · Earned: ' + fmtMoney(acc.earnedThisPeriod);
      }
      if (acc.key === 'credit' && rows.length >= 1 && acc.owed != null) {
        rows[0].textContent = 'Owed: ' + fmtMoney(acc.owed) + ' · Limit: ' + fmtMoney(acc.limit);
      }
      if (acc.key === 'invest' && rows.length >= 1) {
        rows[0].textContent = 'Today: ' + fmtMoney(acc.todayChange) + ' (' + acc.todayChangePct + '%) · ROI ' + acc.roiPct + '%';
      }
    });

    const tbody = document.getElementById('activityTableBody');
    if (tbody && global.transactionsMock && global.transactionsMock.length) {
      tbody.innerHTML = global.transactionsMock.map(tx => `
        <tr>
          <td>${formatNTDate(tx.date)}</td>
          <td><span style="font-weight: 600;">${tx.desc}</span></td>
          <td><span style="font-size: 11px; color: #64748b;">Checking Account</span></td>
          <td><span class="type-label ${tx.typeClass}">${tx.type}</span></td>
          <td style="text-align: right;" class="${tx.amount > 0 ? 'amount-credit' : 'amount-debit'}">
            ${tx.amount > 0 ? '+' : ''}${Number(tx.amount).toLocaleString(undefined, { minimumFractionDigits: 2 })} USD
          </td>
        </tr>`).join('');
    }

    hydrateHeader();
    if (global.NT_updateBalancesAsOf) global.NT_updateBalancesAsOf();

    if ((global.currentActiveView === 'Overview' || !global.currentActiveView) &&
        typeof global.switchActiveSubAccount === 'function') {
      global.switchActiveSubAccount(global._activeSubAccount || 'checking', false, { force: true });
    }
  }

  async function loadStatementsAll() {
    const tabs = ['unified', 'checking', 'savings', 'credit', 'invest'];
    await Promise.all(tabs.map(async tab => {
      state.statements[tab] = await apiGet('/statements', { tab });
    }));
  }

  async function loadDashboard() {
    const loading = document.getElementById('ntLoadingBanner');
    if (loading) loading.style.display = 'block';
    try {
      const [overview, profile, balances, beneficiaries, transferHistory, pendingApprovals, analytics] = await Promise.all([
        apiGet('/dashboard/overview'),
        apiGet('/client/profile'),
        apiGet('/accounts/balances'),
        apiGet('/beneficiaries'),
        apiGet('/transfers/history', { page: 1, size: 50 }),
        apiGet('/approvals/pending'),
        apiGet('/analytics/dashboard')
      ]);
      state.overview = overview;
      state.profile = profile;
      state.balances = balances;
      state.beneficiaries = beneficiaries;
      state.transferHistory = transferHistory;
      state.pendingApprovals = pendingApprovals;
      state.analytics = analytics;
      await loadStatementsAll();
      state.connected = true;
      state.ready = true;
      applyGlobalMocks();
      syncBalances();
      hideConnectionBanner();
      if (global.NTNotifications) {
        await global.NTNotifications.refreshBadge();
      }
      console.info('[NT API] Live data ready —', ACCOUNT, profile.displayName);
    } catch (e) {
      console.warn('[NT API] Backend unreachable — embedded mocks only.', e.message);
      state.connected = false;
      state.ready = true;
      showConnectionBanner('Unable to reach banking services. Displaying cached demo data.');
    } finally {
      if (loading) loading.style.display = 'none';
    }
  }

  async function reloadView(viewName) {
    if (!state.connected) return;
    if (viewName === 'TransferHistory' && typeof global.renderTxHistory === 'function') {
      await global.renderTxHistory();
    }
    if (viewName === 'PendingApprovals') {
      state.pendingApprovals = await apiGet('/approvals/pending');
      applyGlobalMocks();
      if (typeof global.initPendingApprovalsView === 'function') global.initPendingApprovalsView();
    }
    if (viewName === 'Beneficiaries' && typeof global.initBeneficiariesView === 'function') {
      state.beneficiaries = await apiGet('/beneficiaries');
      applyGlobalMocks();
      global.initBeneficiariesView();
    }
    if (global.NTReporting && global.NTReporting.isReportingView(viewName)) {
      const tr = document.getElementById('analyticsTimeRange')?.value || '7d';
      state.analytics = await apiGet('/analytics/dashboard', { timeRange: tr });
      if (typeof global.updateAnalytics === 'function') {
        await global.updateAnalytics();
      }
    }
    if (viewName === 'Notifications' && global.NTNotifications) {
      await global.NTNotifications.renderCenter();
    }
  }

  global.NTApi = {
    state, loadDashboard, hydrateOverview, hydrateHeader, reloadView,
    apiGet, apiPost, apiPatch, ACCOUNT, API_BASE,
    postInternalTransfer: body => apiPost('/transfers/internal', body),
    postAchTransfer: body => apiPost('/transfers/ach', body),
    postWireTransfer: body => apiPost('/transfers/wire', body),
    postIntlTransfer: body => apiPost('/transfers/international', body),
    createBeneficiary: body => apiPost('/beneficiaries', body),
    updateBeneficiaryLimits: (code, single, daily) => apiPatch('/beneficiaries/' + code + '/limits', { single, daily }),
    updateBeneficiaryTrust: (code, trusted) => apiPatch('/beneficiaries/' + code + '/trust', { trusted }),
    updateBeneficiaryStatus: (code, status) => apiPatch('/beneficiaries/' + code + '/status', { status }),
    fetchStatements: (params) => apiGet('/statements', params),
    requestOtp: () => apiPost('/otp/request'),
    verifyOtp: code => apiPost('/otp/verify', null, { code }),
    approve: (ref, action) => apiPost('/approvals/' + ref + '/' + action),
    freezeCard: frozen => apiPatch('/cards/freeze', { frozen }),
    fetchNotifications: () => apiGet('/notifications'),
    fetchUnreadCount: () => apiGet('/notifications/unread-count'),
    markNotificationRead: id => apiPatch('/notifications/' + id + '/read'),
    markAllNotificationsRead: () => apiPatch('/notifications/read-all'),
    refreshAll: async () => { await loadDashboard(); hydrateOverview(); }
  };
})(window);
