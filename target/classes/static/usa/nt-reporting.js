/**
 * Reporting & Analytics — submenu views, charts, KPIs, insights.
 */
(function (global) {
  'use strict';

  const REPORTING_VIEWS = [
    'AnalyticsDashboard',
    'CashFlowReport',
    'TransferReport',
    'PortfolioAllocation',
    'FxExposureReport',
    'RiskComplianceReport',
    'ApprovalMetrics',
    'InsightsReports'
  ];

  const VIEW_LABELS = {
    AnalyticsDashboard: 'Portfolio Dashboard',
    CashFlowReport: 'Cash Flow',
    TransferReport: 'Transfer Activity',
    PortfolioAllocation: 'Portfolio Allocation',
    FxExposureReport: 'FX Exposure',
    RiskComplianceReport: 'Risk & Compliance',
    ApprovalMetrics: 'Approval Metrics',
    InsightsReports: 'Insights & Exports',
    ReportingAnalytics: 'Portfolio Dashboard'
  };

  const SECTION_META = {
    AnalyticsDashboard: {
      portal: 'Strategic Insights',
      title: 'Portfolio Dashboard',
      subtitle: 'Consolidated KPIs, charts, and operational intelligence across all accounts.'
    },
    CashFlowReport: {
      portal: 'Strategic Insights',
      title: 'Cash Flow Analysis',
      subtitle: 'Inflow vs outflow trends, liquidity movement, and weekly settlement patterns.'
    },
    TransferReport: {
      portal: 'Strategic Insights',
      title: 'Transfer Activity',
      subtitle: 'Volume by rail — internal, ACH, wire, and international — with settlement status.'
    },
    PortfolioAllocation: {
      portal: 'Strategic Insights',
      title: 'Portfolio Allocation',
      subtitle: 'Asset distribution across checking, savings, credit, and managed portfolios.'
    },
    FxExposureReport: {
      portal: 'Strategic Insights',
      title: 'FX Exposure',
      subtitle: 'Currency concentration, volatility bands, and cross-border fee impact.'
    },
    RiskComplianceReport: {
      portal: 'Strategic Insights',
      title: 'Risk & Compliance',
      subtitle: 'Flagged transactions, OFAC holds, and compliance queue health.'
    },
    ApprovalMetrics: {
      portal: 'Strategic Insights',
      title: 'Approval Performance',
      subtitle: 'Authorization funnel, SLA timing, and dual-control workflow metrics.'
    },
    InsightsReports: {
      portal: 'Strategic Insights',
      title: 'Insights & Exports',
      subtitle: 'AI-generated observations and downloadable audit packs.'
    }
  };

  global.analyticsData = global.analyticsData || {
    kpis: {},
    charts: {},
    insights: [],
    filters: { timeRange: '7d', accountScope: 'all', currency: 'all', txType: 'all' }
  };

  let analyticsRefreshInterval = null;
  let currentReportingView = 'AnalyticsDashboard';

  function isReportingView(viewName) {
    return REPORTING_VIEWS.indexOf(viewName) >= 0 || viewName === 'ReportingAnalytics';
  }

  function normalizeView(viewName) {
    return viewName === 'ReportingAnalytics' ? 'AnalyticsDashboard' : viewName;
  }

  function sharedFiltersHtml() {
    return `
      <div class="global-filters">
        <div class="filter-group">
          <label class="filter-label">Time Range</label>
          <select class="filter-select" id="analyticsTimeRange" onchange="updateAnalytics()">
            <option value="today">Today</option>
            <option value="7d" selected>Last 7 days</option>
            <option value="30d">Last 30 days</option>
            <option value="quarter">Quarter</option>
            <option value="year">Year</option>
          </select>
        </div>
        <div class="filter-group">
          <label class="filter-label">Account Scope</label>
          <select class="filter-select" id="analyticsAccountScope" onchange="updateAnalytics()">
            <option value="all" selected>All Accounts</option>
            <option value="checking">Checking Account</option>
            <option value="savings">Savings Vault</option>
            <option value="credit">Apex Credit Line</option>
            <option value="invest">Managed Portfolio</option>
          </select>
        </div>
        <div class="filter-group">
          <label class="filter-label">Region / Currency</label>
          <select class="filter-select" id="analyticsCurrency" onchange="updateAnalytics()">
            <option value="all" selected>All Currencies</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
            <option value="GBP">GBP</option>
            <option value="JPY">JPY</option>
            <option value="CHF">CHF</option>
          </select>
        </div>
        <div class="filter-group">
          <label class="filter-label">Transaction Type</label>
          <select class="filter-select" id="analyticsTxType" onchange="updateAnalytics()">
            <option value="all" selected>All Types</option>
            <option value="transfers">Transfers</option>
            <option value="fees">Fees</option>
            <option value="fx">FX Conversions</option>
            <option value="investments">Investments</option>
            <option value="approvals">Approvals</option>
          </select>
        </div>
      </div>`;
  }

  function exportButtonsHtml() {
    return `
      <div class="analytics-export-controls">
        <button type="button" class="export-btn primary" onclick="exportAnalyticsReport('pdf')">Export Report (PDF)</button>
        <button type="button" class="export-btn" onclick="exportAnalyticsReport('csv')">Export Data (CSV)</button>
        <button type="button" class="export-btn" onclick="exportAnalyticsReport('json')">Export Audit Pack (JSON)</button>
      </div>`;
  }

  const TEMPLATES = {
    AnalyticsDashboard: function () {
      return `
        <div class="analytics-header report-page-header">
          <div>
            <h1 class="analytics-title">Portfolio Dashboard</h1>
            <p class="analytics-subtitle">Consolidated performance, liquidity, and risk metrics.</p>
          </div>
          ${exportButtonsHtml()}
        </div>
        ${sharedFiltersHtml()}
        <div class="kpi-intelligence-row" id="kpiCardsContainer"></div>
        <div class="analytics-grid">
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Cash Flow Analysis</h3><p class="chart-subtitle">Inflow vs Outflow</p></div><div class="chart-container" id="cashFlowChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Transfer Activity</h3><p class="chart-subtitle">By transfer type</p></div><div class="chart-container" id="transferActivityChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Account Distribution</h3><p class="chart-subtitle">Portfolio allocation</p></div><div class="chart-container" id="accountDistributionChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">FX Exposure</h3><p class="chart-subtitle">Currency exposure</p></div><div class="chart-container" id="fxExposureChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Risk & Compliance</h3><p class="chart-subtitle">Risk heatmap</p></div><div class="chart-container" id="riskComplianceChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Approval Performance</h3><p class="chart-subtitle">Funnel analysis</p></div><div class="chart-container" id="approvalPerformanceChart"></div></div>
        </div>
        <div class="insight-engine">
          <div class="insight-header"><div class="insight-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/></svg></div><h3 class="insight-title">Portfolio Insights</h3></div>
          <div class="insight-list" id="insightList"></div>
          <p class="analytics-disclaimer">Insights are for operational planning only — not investment advice.</p>
        </div>`;
    },

    CashFlowReport: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">Cash Flow Analysis</h1><p class="analytics-subtitle">Track liquidity inflows and outflows across your family office accounts.</p></div>
        ${sharedFiltersHtml()}
        <div class="kpi-intelligence-row" id="kpiCardsContainer"></div>
        <div class="analytics-grid" style="grid-template-columns: 1fr;">
          <div class="analytics-chart-card" style="min-height: 320px;"><div class="chart-header"><h3 class="chart-title">Weekly Cash Flow</h3></div><div class="chart-container" id="cashFlowChart" style="min-height: 260px;"></div></div>
        </div>
        <div class="report-detail-table-wrap" id="cashFlowDetailTable"></div>`;
    },

    TransferReport: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">Transfer Activity</h1><p class="analytics-subtitle">Breakdown of internal, ACH, wire, and international movements.</p></div>
        ${sharedFiltersHtml()}
        <div class="analytics-grid" style="grid-template-columns: 1fr 1fr;">
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Transfers by Type</h3></div><div class="chart-container" id="transferActivityChart"></div></div>
          <div class="analytics-chart-card"><div class="chart-header"><h3 class="chart-title">Approval Funnel</h3></div><div class="chart-container" id="approvalPerformanceChart"></div></div>
        </div>
        <div class="report-detail-table-wrap" id="transferDetailTable"></div>`;
    },

    PortfolioAllocation: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">Portfolio Allocation</h1><p class="analytics-subtitle">Market value distribution across account products.</p></div>
        ${sharedFiltersHtml()}
        <div class="kpi-intelligence-row" id="kpiCardsContainer"></div>
        <div class="analytics-grid" style="grid-template-columns: 1fr 1fr;">
          <div class="analytics-chart-card"><div class="chart-container" id="accountDistributionChart"></div></div>
          <div class="report-detail-table-wrap" id="allocationDetailTable"></div>
        </div>`;
    },

    FxExposureReport: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">FX Exposure</h1><p class="analytics-subtitle">Currency concentration and volatility indicators.</p></div>
        ${sharedFiltersHtml()}
        <div class="analytics-grid" style="grid-template-columns: 1fr;">
          <div class="analytics-chart-card"><div class="chart-container" id="fxExposureChart" style="min-height: 240px;"></div></div>
        </div>
        <div class="report-detail-table-wrap" id="fxDetailTable"></div>`;
    },

    RiskComplianceReport: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">Risk & Compliance</h1><p class="analytics-subtitle">Operational risk signals and compliance holds.</p></div>
        ${sharedFiltersHtml()}
        <div class="analytics-grid" style="grid-template-columns: 1fr 1fr;">
          <div class="analytics-chart-card"><div class="chart-container" id="riskComplianceChart"></div></div>
          <div class="report-detail-table-wrap" id="riskDetailTable"></div>
        </div>`;
    },

    ApprovalMetrics: function () {
      return `
        <div class="report-page-header"><h1 class="analytics-title">Approval Performance</h1><p class="analytics-subtitle">Dual-control workflow and authorization SLA metrics.</p></div>
        ${sharedFiltersHtml()}
        <div class="kpi-intelligence-row" id="kpiCardsContainer"></div>
        <div class="analytics-grid" style="grid-template-columns: 1fr;">
          <div class="analytics-chart-card"><div class="chart-container" id="approvalPerformanceChart" style="min-height: 280px;"></div></div>
        </div>
        <div class="report-detail-table-wrap" id="approvalDetailTable"></div>`;
    },

    InsightsReports: function () {
      return `
        <div class="analytics-header report-page-header">
          <div><h1 class="analytics-title">Insights & Exports</h1><p class="analytics-subtitle">Generated observations and regulatory export packs.</p></div>
          ${exportButtonsHtml()}
        </div>
        ${sharedFiltersHtml()}
        <div class="insight-engine" style="margin-top: 8px;">
          <div class="insight-header"><div class="insight-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/></svg></div><h3 class="insight-title">Operational Insights</h3></div>
          <div class="insight-list" id="insightList"></div>
          <p class="analytics-disclaimer">Insights are generated from historical account activity.</p>
        </div>
        <div class="report-detail-table-wrap" id="insightsMetaTable"></div>`;
    }
  };

  /* ——— Chart & KPI engines (render only if container exists) ——— */

  function calculateKPIs() {
    const f = global.analyticsData.filters;
    let mult = f.timeRange === 'today' ? 0.1 : f.timeRange === '30d' ? 4 : f.timeRange === 'quarter' ? 12 : f.timeRange === 'year' ? 52 : 1;
    let acct = f.accountScope === 'checking' ? 0.03 : f.accountScope === 'savings' ? 0.52 : f.accountScope === 'credit' ? 0.001 : f.accountScope === 'invest' ? 0.44 : 1;
    const api = global.NTApi?.state?.analytics?.kpis;
    if (api && global.NTApi?.state?.connected) {
      global.analyticsData.kpis = {
        totalBalance: Number(api.totalBalance),
        netCashFlow: Number(api.netCashFlow),
        totalTransfers: Number(api.totalTransfers),
        fxFeesPaid: Number(api.fxFeesPaid),
        approvalRate: Number(api.approvalRate),
        failedRate: Number(api.failedRate),
        portfolioPerformance: Number(api.portfolioPerformance)
      };
      return;
    }
    global.analyticsData.kpis = {
      totalBalance: 73425.68 * acct,
      netCashFlow: 1852 * mult,
      totalTransfers: Math.round(45 * mult),
      fxFeesPaid: 12.5 * mult,
      approvalRate: 92.5,
      failedRate: 3.2,
      portfolioPerformance: 12.4
    };
  }

  function renderKPICards() {
    const container = document.getElementById('kpiCardsContainer');
    if (!container) return;
    const kpis = [
      { label: 'Total Balance', value: global.analyticsData.kpis.totalBalance, format: 'currency', trend: 2.4, positive: true },
      { label: 'Net Cash Flow', value: global.analyticsData.kpis.netCashFlow, format: 'currency', trend: 12, positive: true },
      { label: 'Total Transfers', value: global.analyticsData.kpis.totalTransfers, format: 'number', trend: 8.5, positive: true },
      { label: 'FX Fees Paid', value: global.analyticsData.kpis.fxFeesPaid, format: 'currency', trend: -5.2, positive: false },
      { label: 'Approval Rate', value: global.analyticsData.kpis.approvalRate, format: 'percent', trend: 1.2, positive: true },
      { label: 'Failed Rate', value: global.analyticsData.kpis.failedRate, format: 'percent', trend: -0.8, positive: true },
      { label: 'Portfolio Performance', value: global.analyticsData.kpis.portfolioPerformance, format: 'percent', trend: 3.1, positive: true }
    ];
    container.innerHTML = kpis.map(function (kpi) {
      const v = kpi.format === 'currency' ? '$' + Number(kpi.value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
        : kpi.format === 'percent' ? Number(kpi.value).toFixed(1) + '%' : Number(kpi.value).toLocaleString();
      const tc = kpi.positive ? 'positive' : 'negative';
      return '<div class="kpi-card" role="button" tabindex="0" onclick="openDrilldown(\'' + kpi.label + '\')"><div class="kpi-label">' + kpi.label + '</div><div class="kpi-value">' + v + '</div><div class="kpi-trend ' + tc + '"><span>' + (kpi.trend > 0 ? '↑' : '↓') + ' ' + Math.abs(kpi.trend) + '%</span></div></div>';
    }).join('');
  }

  function chartDataFromApi() {
    const c = global.NTApi?.state?.analytics?.charts;
    const b = global.NTApi?.state?.analytics?.transferBreakdown;
    return { charts: c, breakdown: b };
  }

  function renderCashFlowChart() {
    const el = document.getElementById('cashFlowChart');
    if (!el) return;
    const api = chartDataFromApi().charts;
    const days = api?.cashFlowLabels || ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const inflow = api?.cashFlowInflow || [1200, 950, 1450, 1100, 1800, 750, 600];
    const outflow = api?.cashFlowOutflow || [850, 720, 950, 880, 1100, 450, 380];
    const maxV = Math.max.apply(null, inflow.concat(outflow));
    const h = 200, w = 400, bw = 20, gap = 35;
    let svg = '<svg width="100%" height="100%" viewBox="0 0 ' + w + ' ' + h + '">';
    days.forEach(function (day, i) {
      const x = i * (bw * 2 + gap) + 20;
      const ih = (inflow[i] / maxV) * (h - 50);
      const oh = (outflow[i] / maxV) * (h - 50);
      svg += '<rect x="' + x + '" y="' + (h - ih - 20) + '" width="' + bw + '" height="' + ih + '" fill="#004225" rx="2"/>';
      svg += '<rect x="' + (x + bw + 2) + '" y="' + (h - oh - 20) + '" width="' + bw + '" height="' + oh + '" fill="#b3995d" rx="2"/>';
      svg += '<text x="' + (x + bw) + '" y="' + (h - 5) + '" text-anchor="middle" font-size="10" fill="#64748b">' + day + '</text>';
    });
    svg += '</svg><div class="chart-legend"><div class="legend-item"><div class="legend-color" style="background:#004225"></div><span>Inflow</span></div><div class="legend-item"><div class="legend-color" style="background:#b3995d"></div><span>Outflow</span></div></div>';
    el.innerHTML = svg;
  }

  function renderTransferActivityChart() {
    const el = document.getElementById('transferActivityChart');
    if (!el) return;
    const b = chartDataFromApi().breakdown;
    const data = b ? [
      { type: 'Internal', value: b.Internal || 0, color: '#004225' },
      { type: 'ACH', value: b.ACH || 0, color: '#b3995d' },
      { type: 'Wire', value: b.Wire || 0, color: '#075985' },
      { type: 'International', value: b.International || 0, color: '#d97706' }
    ] : [
      { type: 'Internal', value: 35, color: '#004225' },
      { type: 'ACH', value: 28, color: '#b3995d' },
      { type: 'Wire', value: 22, color: '#075985' },
      { type: 'International', value: 15, color: '#d97706' }
    ];
    const maxV = Math.max.apply(null, data.map(function (d) { return d.value; })) || 1;
    let svg = '<svg width="100%" viewBox="0 0 400 200">';
    data.forEach(function (item, i) {
      const y = i * 50 + 20;
      const barW = (item.value / maxV) * 280;
      svg += '<text x="0" y="' + (y + 18) + '" font-size="11" fill="#475569" font-weight="600">' + item.type + '</text>';
      svg += '<rect x="80" y="' + y + '" width="' + barW + '" height="28" fill="' + item.color + '" rx="4"/>';
      svg += '<text x="' + (barW + 90) + '" y="' + (y + 18) + '" font-size="12" font-weight="700">' + item.value + '</text>';
    });
    svg += '</svg>';
    el.innerHTML = svg;
  }

  function renderAccountDistributionChart() {
    const el = document.getElementById('accountDistributionChart');
    if (!el) return;
    const slices = chartDataFromApi().charts?.accountDistribution;
    const data = slices && slices.length ? slices.map(function (s, i) {
      const colors = ['#004225', '#b3995d', '#075985', '#dc2626'];
      const total = slices.reduce(function (sum, x) { return sum + Number(x.value); }, 0);
      return { name: s.name, value: total ? Math.round(Number(s.value) / total * 100) : 0, color: colors[i % colors.length] };
    }) : [
      { name: 'Savings Vault', value: 52, color: '#004225' },
      { name: 'Managed Portfolio', value: 44, color: '#075985' },
      { name: 'Checking', value: 3, color: '#b3995d' },
      { name: 'Credit', value: 1, color: '#dc2626' }
    ];
    const total = data.reduce(function (s, d) { return s + d.value; }, 0) || 1;
    const w = 400, h = 200, cx = 140, cy = 100, r = 70;
    let svg = '<svg width="100%" viewBox="0 0 ' + w + ' ' + h + '">';
    var start = 0;
    data.forEach(function (item) {
      var ang = (item.value / total) * 2 * Math.PI;
      var x1 = cx + r * Math.cos(start), y1 = cy + r * Math.sin(start);
      var x2 = cx + r * Math.cos(start + ang), y2 = cy + r * Math.sin(start + ang);
      var large = ang > Math.PI ? 1 : 0;
      svg += '<path d="M ' + cx + ' ' + cy + ' L ' + x1 + ' ' + y1 + ' A ' + r + ' ' + r + ' 0 ' + large + ' 1 ' + x2 + ' ' + y2 + ' Z" fill="' + item.color + '" stroke="#fff" stroke-width="2"/>';
      start += ang;
    });
    svg += '<circle cx="' + cx + '" cy="' + cy + '" r="40" fill="#fff"/>';
    var ly = 20;
    data.forEach(function (item) {
      svg += '<rect x="260" y="' + ly + '" width="12" height="12" fill="' + item.color + '" rx="2"/>';
      svg += '<text x="278" y="' + (ly + 10) + '" font-size="10" fill="#475569">' + item.name + ' (' + item.value + '%)</text>';
      ly += 22;
    });
    svg += '</svg>';
    el.innerHTML = svg;
  }

  function renderFXExposureChart() {
    const el = document.getElementById('fxExposureChart');
    if (!el) return;
    var data = [
      { currency: 'USD', exposure: 85, volatility: 'Low' },
      { currency: 'EUR', exposure: 8, volatility: 'Medium' },
      { currency: 'GBP', exposure: 4, volatility: 'Medium' },
      { currency: 'JPY', exposure: 2, volatility: 'High' },
      { currency: 'CHF', exposure: 1, volatility: 'Low' }
    ];
    var html = '<div style="display:flex;flex-direction:column;gap:12px;padding:12px;">';
    data.forEach(function (item) {
      var vc = item.volatility === 'Low' ? 'risk-low' : item.volatility === 'Medium' ? 'risk-medium' : 'risk-high';
      html += '<div style="display:flex;align-items:center;gap:12px;"><span style="width:40px;font-weight:600;font-size:12px;">' + item.currency + '</span><div style="flex:1;height:22px;background:#f1f5f9;border-radius:4px;"><div style="width:' + item.exposure + '%;height:100%;background:linear-gradient(90deg,#004225,#b3995d);border-radius:4px;"></div></div><span style="width:36px;font-weight:700;font-size:12px;">' + item.exposure + '%</span><span class="risk-heatmap-cell ' + vc + '" style="width:56px;text-align:center;font-size:10px;">' + item.volatility + '</span></div>';
    });
    html += '</div>';
    el.innerHTML = html;
  }

  function renderRiskComplianceChart() {
    const el = document.getElementById('riskComplianceChart');
    if (!el) return;
    var data = [
      { category: 'High-Risk Transfers', count: 3, level: 'critical' },
      { category: 'Flagged Transactions', count: 12, level: 'high' },
      { category: 'Compliance Holds', count: 5, level: 'medium' },
      { category: 'Sanction Triggers', count: 0, level: 'low' }
    ];
    var html = '<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:12px;">';
    data.forEach(function (item) {
      html += '<div class="risk-heatmap-cell risk-' + item.level + '" style="padding:16px;text-align:center;"><div style="font-size:22px;font-weight:700;">' + item.count + '</div><div style="font-size:9px;text-transform:uppercase;margin-top:4px;">' + item.category + '</div></div>';
    });
    html += '</div>';
    el.innerHTML = html;
  }

  function renderApprovalPerformanceChart() {
    const el = document.getElementById('approvalPerformanceChart');
    if (!el) return;
    var data = [
      { stage: 'Initiated', value: 100, color: '#94a3b8' },
      { stage: 'Pending Approval', value: 75, color: '#d97706' },
      { stage: 'Approved', value: 65, color: '#004225' },
      { stage: 'Rejected', value: 8, color: '#dc2626' },
      { stage: 'Escalated', value: 2, color: '#7c3aed' }
    ];
    var html = '<div class="approval-funnel" style="display:flex;flex-direction:column;gap:12px;padding:12px;">';
    data.forEach(function (item) {
      html += '<div class="approval-funnel-stage" role="button" tabindex="0" style="cursor:pointer;padding:12px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;" onclick="openDrilldown(\'' + item.stage + ' Approvals\')">' +
        '<div style="display:flex;justify-content:space-between;font-size:12px;font-weight:600;margin-bottom:8px;"><span>' + item.stage + '</span><span>' + item.value + '</span></div>' +
        '<div style="height:8px;background:#f1f5f9;border-radius:4px;overflow:hidden;"><div style="width:' + item.value + '%;height:100%;background:' + item.color + ';border-radius:4px;"></div></div>' +
        '</div>';
    });
    html += '</div>';
    el.innerHTML = html;
  }

  function generateInsights() {
    var k = global.analyticsData.kpis;
    var apiList = global.NTApi?.state?.analytics?.insights;
    if (apiList && apiList.length) {
      global.analyticsData.insights = apiList.slice();
      return;
    }
    global.analyticsData.insights = [
      'Cash outflows increased compared to the prior period under current filters.',
      'Wire transfers account for the majority of fee volume this period.',
      'International transfers may trigger additional compliance review when thresholds are exceeded.',
      'Savings vault growth is outpacing checking inflows in the selected window.',
      'Approval rate of ' + Number(k.approvalRate).toFixed(1) + '% indicates healthy workflow throughput.',
      'Portfolio performance of ' + Number(k.portfolioPerformance).toFixed(1) + '% is above the internal benchmark.'
    ];
  }

  function renderInsights() {
    var el = document.getElementById('insightList');
    if (!el) return;
    el.innerHTML = global.analyticsData.insights.map(function (t) {
      return '<div class="insight-item"><div class="insight-bullet"></div><div class="insight-text">' + t + '</div></div>';
    }).join('');
  }

  function renderDetailTables() {
    var cashTbl = document.getElementById('cashFlowDetailTable');
    if (cashTbl) {
      var days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
      var rows = days.map(function (d, i) {
        return '<tr><td>' + d + '</td><td>$' + (1200 + i * 80).toLocaleString() + '</td><td>$' + (800 + i * 40).toLocaleString() + '</td><td style="color:#004225;font-weight:600;">$' + (400 + i * 40).toLocaleString() + '</td></tr>';
      }).join('');
      cashTbl.innerHTML = detailTableHtml('Daily Cash Flow Detail', ['Day', 'Inflow', 'Outflow', 'Net'], rows);
    }
    var trTbl = document.getElementById('transferDetailTable');
    if (trTbl) {
      trTbl.innerHTML = detailTableHtml('Transfer Summary', ['Type', 'Count', 'Volume', 'Avg Size'],
        '<tr><td>Internal</td><td>12</td><td>$45,200</td><td>$3,767</td></tr><tr><td>ACH</td><td>28</td><td>$18,400</td><td>$657</td></tr><tr><td>Wire</td><td>8</td><td>$125,000</td><td>$15,625</td></tr><tr><td>International</td><td>4</td><td>$62,500</td><td>$15,625</td></tr>');
    }
    var allocTbl = document.getElementById('allocationDetailTable');
    if (allocTbl) {
      allocTbl.innerHTML = detailTableHtml('Account Breakdown', ['Account', 'Market Value', 'Weight', 'Day Change'],
        '<tr><td>Savings Vault</td><td>$35,180.38</td><td>47.9%</td><td style="color:#16a34a">+0.4%</td></tr><tr><td>Managed Portfolio</td><td>$29,745.30</td><td>40.5%</td><td style="color:#16a34a">+1.2%</td></tr><tr><td>Checking</td><td>$8,245.30</td><td>11.2%</td><td style="color:#dc2626">-0.2%</td></tr><tr><td>Credit Line</td><td>$254.70</td><td>0.4%</td><td>—</td></tr>');
    }
    var fxTbl = document.getElementById('fxDetailTable');
    if (fxTbl) {
      fxTbl.innerHTML = detailTableHtml('FX Positions', ['Currency', 'Exposure %', 'Volatility', 'Fees YTD'],
        '<tr><td>USD</td><td>85%</td><td>Low</td><td>$42.00</td></tr><tr><td>EUR</td><td>8%</td><td>Medium</td><td>$28.50</td></tr><tr><td>GBP</td><td>4%</td><td>Medium</td><td>$12.00</td></tr>');
    }
    var riskTbl = document.getElementById('riskDetailTable');
    if (riskTbl) {
      riskTbl.innerHTML = detailTableHtml('Open Risk Events', ['Category', 'Count', 'Severity', 'Last Updated'],
        '<tr><td>OFAC Hold</td><td>2</td><td>Critical</td><td>Today</td></tr><tr><td>Policy Limit</td><td>5</td><td>High</td><td>Yesterday</td></tr><tr><td>Velocity Alert</td><td>3</td><td>Medium</td><td>2 days ago</td></tr>');
    }
    var apprTbl = document.getElementById('approvalDetailTable');
    if (apprTbl) {
      apprTbl.innerHTML = detailTableHtml('Approval SLA', ['Stage', 'Count', 'Avg Time', 'SLA Met'],
        '<tr><td>Pending</td><td>8</td><td>2.4 hrs</td><td>94%</td></tr><tr><td>Approved</td><td>42</td><td>18 min</td><td>99%</td></tr><tr><td>Rejected</td><td>3</td><td>45 min</td><td>100%</td></tr>');
    }
    var insTbl = document.getElementById('insightsMetaTable');
    if (insTbl) {
      insTbl.innerHTML = detailTableHtml('Export Catalog', ['Report', 'Format', 'Description'],
        '<tr><td>Portfolio Dashboard</td><td>PDF / CSV</td><td>Full KPI and chart pack</td></tr><tr><td>Audit Trail</td><td>JSON</td><td>Machine-readable event log</td></tr><tr><td>Compliance Summary</td><td>PDF</td><td>Risk and approval metrics</td></tr>');
    }
  }

  function detailTableHtml(title, headers, bodyRows) {
    var head = headers.map(function (h) { return '<th>' + h + '</th>'; }).join('');
    return '<div class="report-detail-table"><div class="report-detail-table__title">' + title + '</div><table class="drilldown-table"><thead><tr>' + head + '</tr></thead><tbody>' + bodyRows + '</tbody></table></div>';
  }

  function syncFiltersFromDom() {
    global.analyticsData.filters.timeRange = document.getElementById('analyticsTimeRange')?.value || '7d';
    global.analyticsData.filters.accountScope = document.getElementById('analyticsAccountScope')?.value || 'all';
    global.analyticsData.filters.currency = document.getElementById('analyticsCurrency')?.value || 'all';
    global.analyticsData.filters.txType = document.getElementById('analyticsTxType')?.value || 'all';
  }

  function updateAnalytics() {
    syncFiltersFromDom();
    calculateKPIs();
    renderKPICards();
    renderCashFlowChart();
    renderTransferActivityChart();
    renderAccountDistributionChart();
    renderFXExposureChart();
    renderRiskComplianceChart();
    renderApprovalPerformanceChart();
    generateInsights();
    renderInsights();
    renderDetailTables();
  }

  function startRefresh() {
    if (analyticsRefreshInterval) clearInterval(analyticsRefreshInterval);
    analyticsRefreshInterval = setInterval(function () {
      if (isReportingView(global.currentActiveView)) updateAnalytics();
    }, 30000);
  }

  function renderView(viewName) {
    var v = normalizeView(viewName);
    if (!TEMPLATES[v]) v = 'AnalyticsDashboard';
    currentReportingView = v;
    var container = document.getElementById('dynamicContainer');
    if (!container) return;
    container.innerHTML = TEMPLATES[v]();
    updateAnalytics();
    startRefresh();
  }

  function getSectionMeta(viewName) {
    var v = normalizeView(viewName);
    return SECTION_META[v] || SECTION_META.AnalyticsDashboard;
  }

  function getLabel(viewName) {
    return VIEW_LABELS[normalizeView(viewName)] || viewName;
  }

  /* Drilldown — KPI card detail drawer */
  global.openDrilldown = function (title) {
    var drawer = document.getElementById('drilldownDrawer');
    var overlay = document.getElementById('drilldownOverlay');
    var titleEl = document.getElementById('drilldownTitle');
    var contentEl = document.getElementById('drilldownContent');
    if (!drawer || !contentEl) return;
    titleEl.textContent = title;

    var transactions = [];
    var unified = global.unifiedMock || [];
    var invest = global.investMock || [];
    var pending = global.mockPendingApprovals || [];

    if (title.indexOf('FX') >= 0) {
      transactions = unified.filter(function (tx) {
        return (tx.desc || '').toLowerCase().indexOf('fee') >= 0;
      }).slice(0, 10);
    } else if (title.indexOf('Approval') >= 0 || title.indexOf('Failed') >= 0) {
      transactions = pending.map(function (tx) {
        return {
          date: new Date(tx.timestamp).toISOString().split('T')[0],
          desc: tx.type + ' to ' + tx.beneficiary,
          amount: tx.amount,
          status: tx.status
        };
      }).slice(0, 10);
    } else if (title.indexOf('Portfolio') >= 0) {
      transactions = invest.slice(0, 10);
    } else {
      transactions = unified.slice(0, 10);
    }

    var tableRows = transactions.map(function (tx) {
      var date = tx.date || new Date().toISOString().split('T')[0];
      var desc = tx.desc || tx.type || 'Transaction';
      var amount = tx.amount !== undefined ? Math.abs(tx.amount) : 0;
      var status = tx.status || 'Completed';
      return '<tr><td>' + date + '</td><td>' + desc + '</td><td>$' + amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td><td>' + status + '</td></tr>';
    }).join('');

    var totalVol = transactions.reduce(function (sum, tx) {
      return sum + (tx.amount !== undefined ? Math.abs(tx.amount) : 0);
    }, 0);
    var filters = global.analyticsData.filters;

    contentEl.innerHTML =
      '<div class="drilldown-section"><div class="drilldown-section-title">Breakdown View</div>' +
      '<table class="drilldown-table"><thead><tr><th>Date</th><th>Description</th><th>Amount</th><th>Status</th></tr></thead><tbody>' +
      (tableRows || '<tr><td colspan="4" style="text-align:center;color:#94a3b8;padding:20px;">No transactions found</td></tr>') +
      '</tbody></table></div>' +
      '<div class="drilldown-section"><div class="drilldown-section-title">Summary</div>' +
      '<div style="font-size:12px;color:#475569;line-height:1.8;">' +
      '<div><strong>Total Records:</strong> ' + transactions.length + '</div>' +
      '<div><strong>Total Volume:</strong> $' + totalVol.toLocaleString(undefined, { minimumFractionDigits: 2 }) + '</div></div></div>' +
      '<div class="drilldown-section"><div class="drilldown-section-title">Filters</div>' +
      '<div style="font-size:12px;color:#475569;line-height:1.8;">' +
      '<div><strong>Time:</strong> ' + filters.timeRange + '</div>' +
      '<div><strong>Account:</strong> ' + filters.accountScope + '</div>' +
      '<div><strong>Currency:</strong> ' + filters.currency + '</div>' +
      '<div><strong>Updated:</strong> ' + new Date().toLocaleString() + '</div></div></div>';

    drawer.classList.add('active');
    overlay.classList.add('active');
  };

  global.closeDrilldown = global.closeDrilldown || function () {
    document.getElementById('drilldownDrawer')?.classList.remove('active');
    document.getElementById('drilldownOverlay')?.classList.remove('active');
  };

  global.initReportingAnalyticsView = function () { renderView('AnalyticsDashboard'); };
  global.updateAnalytics = function () {
    if (typeof global._ntUpdateAnalyticsAsync === 'function') {
      global._ntUpdateAnalyticsAsync();
      return;
    }
    updateAnalytics();
  };

  global.NTReporting = {
    REPORTING_VIEWS: REPORTING_VIEWS,
    isReportingView: isReportingView,
    normalizeView: normalizeView,
    renderView: renderView,
    updateAnalytics: updateAnalytics,
    getSectionMeta: getSectionMeta,
    getLabel: getLabel,
    calculateKPIs: calculateKPIs,
    renderKPICards: renderKPICards
  };
})(window);
