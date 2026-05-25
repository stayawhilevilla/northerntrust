package project.northerntrust.app.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.northerntrust.app.dto.*;
import project.northerntrust.app.entity.*;
import project.northerntrust.app.entity.enums.*;
import project.northerntrust.app.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private BeneficiaryRepository beneficiaryRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private StatementLineRepository statementLineRepository;
    @Autowired
    private PaymentCardRepository paymentCardRepository;
    @Autowired
    private KycRepository kycRepository;
    @Autowired
    private TransferService transferService;
    @Autowired
    private OtpService otpService;

    public User requireUser(String accountNumber) {
        return userRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + accountNumber));
    }

    public Map<String, Object> getClientProfile(String accountNumber) {
        User user = requireUser(accountNumber);
        Map<String, Object> profile = buildClientDto(user);
        kycRepository.findByUser(user).ifPresent(kyc -> {
            profile.put("ssn", kyc.getSsn());
            profile.put("ssnMasked", maskSsn(kyc.getSsn()));
            profile.put("taxId", kyc.getTaxId());
            profile.put("bvn", kyc.getBvn());
            profile.put("nin", kyc.getNin());
            profile.put("idType", kyc.getIdType());
            profile.put("idNumber", kyc.getIdNumber());
            profile.put("nationality", kyc.getNationality());
            profile.put("occupation", kyc.getOccupation());
            profile.put("residentialAddress", kyc.getResidentialAddress());
            profile.put("verificationStatus", kyc.getVerificationStatus().name());
        });
        profile.put("kycStatus", user.getKycStatus().name());
        profile.put("accountStatus", user.getAccountStatus().name());
        return profile;
    }

    private Map<String, Object> buildClientDto(User user) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("displayName", user.getFirstName() + " " + user.getLastName());
        client.put("firstName", user.getFirstName());
        client.put("lastName", user.getLastName());
        client.put("userId", user.getClientId() != null ? user.getClientId() : "USR-89024");
        client.put("accountNumber", user.getAccountNumber());
        client.put("email", user.getEmail());
        client.put("phoneNumber", user.getPhoneNumber());
        client.put("dateOfBirth", user.getDateOfBirth() != null ? user.getDateOfBirth().toString() : null);
        client.put("streetAddress", user.getStreetAddress());
        client.put("city", user.getCity());
        client.put("state", user.getState());
        client.put("postalCode", user.getPostalCode());
        client.put("country", user.getCountry());
        return client;
    }

    private String maskSsn(String ssn) {
        if (ssn == null || ssn.length() < 4) {
            return "***-**-****";
        }
        return "***-**-" + ssn.substring(ssn.length() - 4);
    }

    public Map<String, Object> getOverview(String accountNumber) {
        User user = requireUser(accountNumber);
        List<Account> accounts = accountRepository.findByUser(user);

        BigDecimal totalBalance = accounts.stream()
                .map(a -> {
                    if (a.getProductKey() == ProductKey.CREDIT && a.getCreditLimit() != null) {
                        return a.getCreditLimit().subtract(a.getAmountOwed() != null ? a.getAmountOwed() : BigDecimal.ZERO);
                    }
                    return a.getMarketValue() != null ? a.getMarketValue() : a.getBalance();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pending = accounts.stream()
                .map(a -> a.getPendingAmount() != null ? a.getPendingAmount().abs() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", buildClientDto(user));
        result.put("profile", getClientProfile(accountNumber));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPortfolioBalance", totalBalance);
        summary.put("availableBalance", totalBalance.subtract(pending));
        summary.put("pendingSettlements", pending);
        summary.put("monthOverMonthChangePct", 2.4);
        result.put("summary", summary);

        result.put("accounts", accounts.stream().map(this::toAccountDto).collect(Collectors.toList()));
        result.put("recentActivity", getRecentActivity(user, 5));
        result.put("alerts", buildAlerts(user));
        result.put("savingsGoal", savingsGoal());
        result.put("card", getCardDto(user));
        long pendingCount = transferRepository.findByUserAndPendingApprovalTrueOrderByRiskScoreDesc(user).size();
        result.put("insights", Arrays.asList(
                "Portfolio up 2.4% month-over-month driven by managed equity allocation.",
                pendingCount + " transfer(s) awaiting compliance review — highest risk score 92.",
                "Savings Vault APY holding at 4.50% — consider reserve sweep."));
        return result;
    }

    public Map<String, Object> getAccountBalances(String accountNumber) {
        User user = requireUser(accountNumber);
        Map<String, Object> balances = new LinkedHashMap<>();
        for (Account a : accountRepository.findByUser(user)) {
            String key = a.getProductKey().name().toLowerCase();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", a.getDisplayName());
            entry.put("available", a.getAvailableBalance());
            entry.put("balance", a.getBalance());
            entry.put("accountNumber", a.getAccountNumber());
            if (a.getProductKey() == ProductKey.CREDIT) {
                entry.put("available", a.getCreditLimit().subtract(
                        a.getAmountOwed() != null ? a.getAmountOwed() : BigDecimal.ZERO));
            }
            balances.put(key, entry);
        }
        return balances;
    }

    private Map<String, Object> toAccountDto(Account a) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("key", a.getProductKey().name().toLowerCase());
        dto.put("name", a.getDisplayName());
        dto.put("badge", a.getBadge());
        dto.put("balance", a.getBalance());
        dto.put("currency", a.getCurrency());
        dto.put("accountNumber", a.getAccountNumber());
        if (a.getProductKey() == ProductKey.CHECKING) {
            BigDecimal pending = a.getPendingAmount() != null ? a.getPendingAmount() : BigDecimal.ZERO;
            dto.put("ledgerBalance", a.getBalance().subtract(pending));
            dto.put("pendingAmount", pending);
        }
        if (a.getProductKey() == ProductKey.SAVINGS) {
            dto.put("apyPct", a.getApyPercent());
            dto.put("earnedThisPeriod", a.getEarnedThisPeriod());
        }
        if (a.getProductKey() == ProductKey.CREDIT) {
            dto.put("owed", a.getAmountOwed());
            dto.put("limit", a.getCreditLimit());
            dto.put("availableCredit", a.getCreditLimit().subtract(a.getAmountOwed() != null ? a.getAmountOwed() : BigDecimal.ZERO));
        }
        if (a.getProductKey() == ProductKey.INVEST) {
            dto.put("marketValue", a.getMarketValue());
            dto.put("todayChange", a.getTodayChange());
            dto.put("todayChangePct", a.getTodayChangePct());
            dto.put("roiPct", a.getRoiPercent());
        }
        return dto;
    }

    private List<Map<String, Object>> getRecentActivity(User user, int limit) {
        return statementLineRepository.findByUserOrderByLineDateDesc(user).stream()
                .limit(limit)
                .map(line -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", line.getLineDate().format(DISPLAY_DATE));
                    m.put("description", line.getDescription());
                    m.put("counterparty", line.getSource());
                    m.put("type", line.getChannel() != null ? line.getChannel() + " Transfer" : line.getLineType());
                    m.put("amount", line.getAmount());
                    m.put("currency", "USD");
                    return m;
                }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildAlerts(User user) {
        long pending = transferRepository.findByUserAndPendingApprovalTrueOrderByRiskScoreDesc(user).size();
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (pending > 0) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("severity", "danger");
            a.put("title", pending + " transfer(s) require approval");
            a.put("body", "Compliance holds detected on high-risk international wires.");
            alerts.add(a);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("severity", "info");
        info.put("title", "Savings goal on track");
        info.put("body", "You are 60% toward your $150,000 reserve target.");
        alerts.add(info);
        return alerts;
    }

    private Map<String, Object> savingsGoal() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("target", 150000);
        g.put("saved", 90000);
        g.put("autoSaveEnabled", true);
        g.put("autoSavePct", 5);
        return g;
    }

    private Map<String, Object> getCardDto(User user) {
        return paymentCardRepository.findByUser(user).map(card -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("lastFour", card.getLastFour());
            c.put("holder", card.getCardHolder());
            c.put("expires", card.getExpires());
            c.put("frozen", card.getFrozen());
            return c;
        }).orElse(Collections.emptyMap());
    }

    public List<Map<String, Object>> getBeneficiaries(String accountNumber, String type, String search) {
        User user = requireUser(accountNumber);
        List<Beneficiary> list = beneficiaryRepository.findByUser(user);
        return list.stream()
                .filter(b -> type == null || type.isEmpty() || "all".equalsIgnoreCase(type)
                        || b.getBeneficiaryType().name().equalsIgnoreCase(type))
                .filter(b -> search == null || search.isEmpty()
                        || b.getDisplayName().toLowerCase().contains(search.toLowerCase())
                        || (b.getAccountNumber() != null && b.getAccountNumber().contains(search)))
                .map(this::toBeneficiaryDto)
                .collect(Collectors.toList());
    }

    public Map<String, Object> toBeneficiaryDto(Beneficiary b) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("beneficiaryId", b.getBeneficiaryCode());
        dto.put("type", b.getBeneficiaryType().name());
        dto.put("displayName", b.getDisplayName());
        dto.put("relationship", b.getRelationship());
        dto.put("destinationDetails", buildDestinationDetails(b));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("single", b.getSingleLimit());
        limits.put("daily", b.getDailyLimit());
        dto.put("transferLimits", limits);
        dto.put("status", b.getStatus().name());
        dto.put("isTrusted", b.getTrusted());
        dto.put("trustLevel", b.getTrustLevel().name());
        dto.put("createdAt", b.getCreatedAt() != null ? b.getCreatedAt().toLocalDate().toString() : "");
        dto.put("lastUsedAt", b.getLastUsedAt() != null
                ? b.getLastUsedAt().format(DISPLAY_DATE) : "Never Used");
        return dto;
    }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return "";
        }
        if (accountNumber.contains("•")) {
            return accountNumber;
        }
        String digits = accountNumber.replaceAll("\\s+", "");
        if (digits.length() <= 4) {
            return digits;
        }
        return "•••• •••• •••• " + digits.substring(digits.length() - 4);
    }

    private Map<String, Object> buildDestinationDetails(Beneficiary b) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (b.getBeneficiaryType()) {
            case BANK:
                d.put("bankName", b.getBankName());
                d.put("accountNumber", maskAccountNumber(b.getAccountNumber()));
                d.put("routingOrSwift", b.getRoutingOrSwift());
                d.put("country", b.getCountry());
                break;
            case INTERNAL:
                d.put("userId", b.getDestinationUserId());
                d.put("emailOrPhone", b.getEmailOrPhone());
                break;
            case CREDIT:
                d.put("creditAccountId", b.getCreditAccountId());
                d.put("accountType", "NT_CREDIT_LINE");
                d.put("outstandingBalance", 2500.00);
                d.put("dueDate", "June 15, 2026");
                break;
            case INVESTMENT:
                d.put("portfolioId", b.getPortfolioId());
                d.put("brokerOrManagedAccount", true);
                d.put("riskLevel", "Low Risk");
                d.put("marketValue", 17500.00);
                break;
            default:
                break;
        }
        return d;
    }

    public Map<String, Object> getTransferHistory(String accountNumber, String search, String type,
                                                  String status, String period, int page, int size) {
        User user = requireUser(accountNumber);
        List<Transfer> all = transferRepository.findByUserOrderByCreatedAtDesc(user);

        LocalDateTime cutoff = periodCutoff(period);
        List<Transfer> filtered = all.stream()
                .filter(t -> cutoff == null || (t.getCreatedAt() != null && t.getCreatedAt().isAfter(cutoff)))
                .filter(t -> type == null || "all".equalsIgnoreCase(type) || mapTransferType(t).equalsIgnoreCase(type))
                .filter(t -> status == null || "all".equalsIgnoreCase(status)
                        || statusMatches(t, status))
                .filter(t -> search == null || search.isEmpty()
                        || t.getReference().toLowerCase().contains(search.toLowerCase())
                        || (t.getCounterpartyName() != null && t.getCounterpartyName().toLowerCase().contains(search.toLowerCase())))
                .collect(Collectors.toList());

        BigDecimal totalVol = filtered.stream().map(Transfer::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long settled = filtered.stream().filter(t -> t.getDisplayStatus() == DisplayTransferStatus.Settled).count();
        long held = filtered.stream().filter(t -> t.getDisplayStatus() != null && Arrays.asList(
                DisplayTransferStatus.Compliance_Hold, DisplayTransferStatus.OFAC_Hold,
                DisplayTransferStatus.Processing, DisplayTransferStatus.Awaiting_Treasury_Approval,
                DisplayTransferStatus.Awaiting_Verification, DisplayTransferStatus.Blocked_OFAC_Review
        ).contains(t.getDisplayStatus())).count();
        long failed = filtered.stream().filter(t -> t.getDisplayStatus() == DisplayTransferStatus.Failed
                || t.getDisplayStatus() == DisplayTransferStatus.Returned).count();

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<Map<String, Object>> items = filtered.subList(from, to).stream()
                .map(this::toTransferHistoryItem)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVolume", totalVol);
        summary.put("settledCount", settled);
        summary.put("onHoldCount", held);
        summary.put("failedCount", failed);
        result.put("summary", summary);
        result.put("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        return result;
    }

    private Map<String, Object> toTransferHistoryItem(Transfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getReference());
        m.put("date", t.getCreatedAt() != null ? t.getCreatedAt().toString() + "Z" : LocalDateTime.now().toString() + "Z");
        m.put("type", mapTransferType(t));
        m.put("counterparty", t.getCounterpartyName());
        m.put("source", t.getSourceAccountLabel());
        m.put("amount", t.getAmount());
        m.put("currency", t.getCurrency());
        m.put("status", displayStatusLabel(t.getDisplayStatus()));
        return m;
    }

    private String mapTransferType(Transfer t) {
        if (t.getTransferType() == TransferType.INTERNAL) return "Internal";
        if (t.getTransferType() == TransferType.ACH) return "ACH";
        if (t.getTransferType() == TransferType.WIRE) return "Wire";
        if (t.getTransferType() == TransferType.SWIFT) return "International";
        return t.getTransferType().name();
    }

    private String displayStatusLabel(DisplayTransferStatus s) {
        if (s == null) return "Processing";
        if (s == DisplayTransferStatus.Blocked_OFAC_Review) return "Blocked - OFAC Review";
        if (s == DisplayTransferStatus.Awaiting_Treasury_Approval) return "Awaiting Treasury Approval";
        if (s == DisplayTransferStatus.Awaiting_Verification) return "Awaiting Verification";
        if (s == DisplayTransferStatus.Pending_NACHA_Batch) return "Pending NACHA Batch";
        return s.name().replace('_', ' ');
    }

    private boolean statusMatches(Transfer t, String statusFilter) {
        String label = displayStatusLabel(t.getDisplayStatus());
        return label.equalsIgnoreCase(statusFilter)
                || label.replace(" - ", " ").replace("-", " ").equalsIgnoreCase(statusFilter.replace(" - ", " "));
    }

    private LocalDateTime periodCutoff(String period) {
        if (period == null || "all".equalsIgnoreCase(period)) return null;
        LocalDateTime now = LocalDateTime.now();
        if ("today".equalsIgnoreCase(period)) return now.minusDays(1);
        if ("7d".equalsIgnoreCase(period)) return now.minusDays(7);
        if ("30d".equalsIgnoreCase(period)) return now.minusDays(30);
        return null;
    }

    public List<Map<String, Object>> getStatements(String accountNumber, String tab, String period,
                                                   String type, String status, String search) {
        User user = requireUser(accountNumber);
        List<StatementLine> lines = statementLineRepository.findByUserOrderByLineDateDesc(user);

        ProductKey keyFilter = tabToProductKey(tab);
        LocalDate periodStart = statementPeriodStart(period);

        return lines.stream()
                .filter(l -> keyFilter == null || keyFilter.equals(l.getProductKey()))
                .filter(l -> periodStart == null || !l.getLineDate().isBefore(periodStart))
                .filter(l -> type == null || type.isEmpty() || "all".equalsIgnoreCase(type)
                        || (l.getLineType() != null && l.getLineType().equalsIgnoreCase(type)))
                .filter(l -> status == null || status.isEmpty() || "all".equalsIgnoreCase(status)
                        || (l.getStatus() != null && l.getStatus().equalsIgnoreCase(status)))
                .filter(l -> search == null || search.isEmpty()
                        || (l.getDescription() != null && l.getDescription().toLowerCase().contains(search.toLowerCase())))
                .map(this::toStatementDto)
                .collect(Collectors.toList());
    }

    private ProductKey tabToProductKey(String tab) {
        if (tab == null || "unified".equalsIgnoreCase(tab) || "all".equalsIgnoreCase(tab)) return null;
        if ("checking".equalsIgnoreCase(tab)) return ProductKey.CHECKING;
        if ("savings".equalsIgnoreCase(tab)) return ProductKey.SAVINGS;
        if ("credit".equalsIgnoreCase(tab)) return ProductKey.CREDIT;
        if ("invest".equalsIgnoreCase(tab)) return ProductKey.INVEST;
        return null;
    }

    private LocalDate statementPeriodStart(String period) {
        if (period == null || "all".equalsIgnoreCase(period)) return null;
        if ("this_month".equalsIgnoreCase(period)) return LocalDate.now().withDayOfMonth(1);
        if ("last_3_months".equalsIgnoreCase(period)) return LocalDate.now().minusMonths(3);
        return null;
    }

    private Map<String, Object> toStatementDto(StatementLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", l.getLineDate().toString());
        m.put("description", l.getDescription());
        m.put("source", l.getSource());
        m.put("type", l.getLineType());
        m.put("amount", l.getAmount());
        m.put("balanceAfter", l.getBalanceAfter());
        m.put("status", l.getStatus());
        m.put("channel", l.getChannel());
        if (l.getAsset() != null) m.put("asset", l.getAsset());
        if (l.getUnits() != null) m.put("units", l.getUnits());
        if (l.getPrice() != null) m.put("price", l.getPrice());
        if (l.getRateLabel() != null) m.put("rate", l.getRateLabel());
        if (l.getCharged() != null) m.put("charged", l.getCharged());
        if (l.getPayment() != null) m.put("payment", l.getPayment());
        if (l.getCreditAvailable() != null) m.put("creditAvailable", l.getCreditAvailable());
        return m;
    }

    public List<Map<String, Object>> getPendingApprovals(String accountNumber) {
        User user = requireUser(accountNumber);
        return transferRepository.findByUserAndPendingApprovalTrueOrderByRiskScoreDesc(user).stream()
                .map(this::toApprovalDto)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toApprovalDto(Transfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getReference());
        m.put("type", mapTransferType(t) + " Transfer");
        m.put("amount", t.getAmount());
        m.put("currency", t.getCurrency());
        m.put("usdEquivalent", t.getUsdEquivalent() != null ? t.getUsdEquivalent() : t.getAmount());
        m.put("source", t.getSourceAccountLabel());
        m.put("beneficiary", t.getCounterpartyName());
        m.put("country", t.getApprovalCountry() != null ? t.getApprovalCountry() : "United States (US)");
        m.put("riskScore", t.getRiskScore());
        m.put("riskLevel", t.getRiskLevel());
        m.put("status", displayStatusLabel(t.getDisplayStatus()));
        m.put("timestamp", t.getCreatedAt() != null ? t.getCreatedAt().toString() + "Z" : LocalDateTime.now().toString() + "Z");
        m.put("flags", parseJsonArray(t.getComplianceFlagsJson()));
        m.put("compliance", parseJsonObject(t.getComplianceJson()));
        m.put("history", parseJsonArray(t.getApprovalHistoryJson()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @Transactional
    public MessageResponse approveTransfer(String accountNumber, String reference, String action) {
        User user = requireUser(accountNumber);
        Transfer t = transferRepository.findByReferenceAndUser(reference, user)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found"));
        if ("approve".equalsIgnoreCase(action)) {
            t.setPendingApproval(false);
            t.setDisplayStatus(DisplayTransferStatus.Settled);
            t.setStatus(TransferStatus.SUCCESS);
        } else if ("reject".equalsIgnoreCase(action)) {
            t.setPendingApproval(false);
            t.setDisplayStatus(DisplayTransferStatus.Failed);
            t.setStatus(TransferStatus.FAILED);
        } else if ("escalate".equalsIgnoreCase(action)) {
            t.setDisplayStatus(DisplayTransferStatus.Compliance_Hold);
        }
        transferRepository.save(t);
        return new MessageResponse(true, "Action " + action + " applied to " + reference);
    }

    public Map<String, Object> getAnalytics(String accountNumber, String timeRange) {
        User user = requireUser(accountNumber);
        List<Account> accounts = accountRepository.findByUser(user);
        BigDecimal totalBalance = accounts.stream()
                .map(a -> a.getMarketValue() != null ? a.getMarketValue() : a.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Transfer> transfers = transferRepository.findByUserOrderByCreatedAtDesc(user);
        long totalTransfers = transfers.size();
        long settled = transfers.stream().filter(t -> t.getDisplayStatus() == DisplayTransferStatus.Settled).count();
        long pending = transfers.stream().filter(t -> Boolean.TRUE.equals(t.getPendingApproval())).count();

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalBalance", totalBalance);
        kpis.put("netCashFlow", new BigDecimal("1852.00"));
        kpis.put("totalTransfers", totalTransfers);
        kpis.put("fxFeesPaid", new BigDecimal("125.00"));
        kpis.put("approvalRate", totalTransfers == 0 ? 0 : settled * 100.0 / totalTransfers);
        kpis.put("failedRate", 3.2);
        kpis.put("portfolioPerformance", 18.4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kpis", kpis);
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("Internal", (int) transfers.stream().filter(t -> t.getTransferType() == TransferType.INTERNAL).count());
        breakdown.put("ACH", (int) transfers.stream().filter(t -> t.getTransferType() == TransferType.ACH).count());
        breakdown.put("Wire", (int) transfers.stream().filter(t -> t.getTransferType() == TransferType.WIRE).count());
        breakdown.put("International", (int) transfers.stream().filter(t -> t.getTransferType() == TransferType.SWIFT).count());
        result.put("transferBreakdown", breakdown);
        result.put("pendingApprovals", pending);
        result.put("insights", Arrays.asList(
                "Wire volume increased 18% vs prior period.",
                "International exposure concentrated in EUR (42%).",
                "Approval queue SLA within target at 94%."));

        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("cashFlowLabels", Arrays.asList("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"));
        charts.put("cashFlowInflow", Arrays.asList(1200, 950, 1852, 880, 1420, 450, 620));
        charts.put("cashFlowOutflow", Arrays.asList(350, 420, 280, 1245, 185, 87, 24));
        charts.put("accountDistribution", accounts.stream().map(a -> {
            Map<String, Object> slice = new LinkedHashMap<>();
            slice.put("name", a.getDisplayName());
            BigDecimal val = a.getMarketValue() != null ? a.getMarketValue() : a.getBalance();
            slice.put("value", val);
            return slice;
        }).collect(Collectors.toList()));
        result.put("charts", charts);

        double multiplier = 1.0;
        if ("today".equalsIgnoreCase(timeRange)) multiplier = 0.15;
        else if ("30d".equalsIgnoreCase(timeRange)) multiplier = 3.5;
        else if ("quarter".equalsIgnoreCase(timeRange)) multiplier = 10;
        else if ("year".equalsIgnoreCase(timeRange)) multiplier = 40;
        kpis.put("netCashFlow", new BigDecimal("1852.00").multiply(BigDecimal.valueOf(multiplier)));
        kpis.put("totalTransfers", Math.max(1, Math.round(totalTransfers * multiplier)));
        kpis.put("fxFeesPaid", new BigDecimal("125.00").multiply(BigDecimal.valueOf(multiplier)));
        result.put("kpis", kpis);
        return result;
    }

    public Map<String, BigDecimal> getFxRates() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("EUR", new BigDecimal("0.92"));
        rates.put("GBP", new BigDecimal("0.79"));
        rates.put("JPY", new BigDecimal("157.20"));
        rates.put("CHF", new BigDecimal("0.90"));
        return rates;
    }

    public Optional<Account> resolveSourceAccount(User user, String productKey) {
        try {
            ProductKey key = ProductKey.valueOf(productKey.toUpperCase());
            return accountRepository.findByUserAndProductKey(user, key);
        } catch (Exception e) {
            return accountRepository.findByUser(user).stream().findFirst();
        }
    }

    @Autowired
    private NotificationService notificationService;

    @Transactional
    public MessageResponse freezeCard(String accountNumber, boolean frozen) {
        User user = requireUser(accountNumber);
        PaymentCard card = paymentCardRepository.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("Card not found"));
        card.setFrozen(frozen);
        paymentCardRepository.save(card);
        notificationService.recordCardFreeze(accountNumber, frozen);
        return new MessageResponse(true, frozen ? "Card frozen" : "Card unfrozen");
    }

    public BigDecimal getAccountBalance(String accountNumber, String productKey) {
        User user = requireUser(accountNumber);
        return resolveSourceAccount(user, productKey)
                .map(Account::getAvailableBalance)
                .orElse(BigDecimal.ZERO);
    }
}
