package project.northerntrust.app.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.northerntrust.app.dto.ExternalTransferRequest;
import project.northerntrust.app.dto.LedgerHistoryResponse;
import project.northerntrust.app.dto.MessageResponse;
import project.northerntrust.app.dto.TransferRequest;
import project.northerntrust.app.entity.*;
import project.northerntrust.app.entity.enums.*;
import project.northerntrust.app.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransferService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferStatusLogRepository transferStatusLogRepository;

    @Autowired
    private PaymentRailRepository paymentRailRepository;

    @Autowired
    private TransferDetailRepository transferDetailRepository;

    @Autowired
    private TransferFeeRepository transferFeeRepository;

    @Autowired
    private StatementLineRepository statementLineRepository;

    @Autowired
    private AmlRiskService amlRiskService;

    @Autowired
    private WireComplianceService wireComplianceService;

    @Transactional
    public MessageResponse performInternalTransfer(TransferRequest request) {
        // ... (existing implementation) ...
        Transfer transfer = new Transfer();
        transfer.setReference("TRX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        transfer.setTransferType(TransferType.INTERNAL);
        transfer.setDirection(TransferDirection.DOMESTIC);
        transfer.setAmount(request.getAmount());
        transfer.setDescription(request.getDescription());
        transfer.setStatus(TransferStatus.CREATED);

        Optional<Account> fromAccountOpt = accountRepository.findByAccountNumber(request.getFromAccountNumber());
        Optional<Account> toAccountOpt = accountRepository.findByAccountNumber(request.getToAccountNumber());

        if (fromAccountOpt.isEmpty()) {
            return new MessageResponse(false, "Sender account not found.");
        }
        if (toAccountOpt.isEmpty()) {
            return new MessageResponse(false, "Recipient account not found.");
        }

        Account sender = fromAccountOpt.get();
        Account receiver = toAccountOpt.get();

        // AML CHECK
        try {
            amlRiskService.analyzeTransfer(sender.getUser(), request.getAmount(), sender.getAccountNumber());
        } catch (RuntimeException e) {
            transfer.setUser(sender.getUser());
            transfer.setFromAccount(sender);
            transfer.setToAccount(receiver);
            transfer.setCurrency(sender.getCurrency());
            transfer = transferRepository.save(transfer);
            logAndFail(transfer, e.getMessage());
            return new MessageResponse(false, e.getMessage());
        }

        transfer.setUser(sender.getUser());
        transfer.setFromAccount(sender);
        transfer.setToAccount(receiver);
        transfer.setCurrency(sender.getCurrency());
        transfer.setCounterpartyName(receiver.getDisplayName());
        transfer.setSourceAccountLabel(sender.getDisplayName());
        transfer.setDisplayStatus(DisplayTransferStatus.Settled);
        
        transfer = transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.CREATED, "Transfer request received.");

        if (sender.getStatus() != AccountStatus.ACTIVE) {
            logAndFail(transfer, "Sender account is not ACTIVE.");
            return new MessageResponse(false, "Sender account is frozen or closed.");
        }

        if (receiver.getStatus() != AccountStatus.ACTIVE) {
            logAndFail(transfer, "Recipient account is not ACTIVE.");
            return new MessageResponse(false, "Recipient account is frozen or closed.");
        }

        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            logAndFail(transfer, "Cross-currency transfers are not supported yet.");
            return new MessageResponse(false, "Sender and receiver must have the same currency.");
        }

        if (sender.getAvailableBalance().compareTo(request.getAmount()) < 0) {
            logAndFail(transfer, "Insufficient funds.");
            return new MessageResponse(false, "Insufficient funds.");
        }

        if (request.getAmount().compareTo(sender.getDailyTransferLimit()) > 0) {
            logAndFail(transfer, "Amount exceeds daily transfer limit.");
            return new MessageResponse(false, "Amount exceeds daily transfer limit.");
        }

        transfer.setStatus(TransferStatus.VALIDATED);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.VALIDATED, "Balances and limits verified successfully.");

        transfer.setStatus(TransferStatus.PENDING_PROCESSING);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.PENDING_PROCESSING, "Deducting funds from sender.");

        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        sender.setAvailableBalance(sender.getAvailableBalance().subtract(request.getAmount()));
        accountRepository.save(sender);

        receiver.setBalance(receiver.getBalance().add(request.getAmount()));
        receiver.setAvailableBalance(receiver.getAvailableBalance().add(request.getAmount()));
        accountRepository.save(receiver);

        createLedgerEntry(sender, transfer, EntryType.DEBIT, request.getAmount(), sender.getBalance());
        createLedgerEntry(receiver, transfer, EntryType.CREDIT, request.getAmount(), receiver.getBalance());

        createStatementLine(sender, sender.getProductKey(), "Internal Transfer to " + receiver.getDisplayName(),
                sender.getDisplayName(), "Withdrawal", request.getAmount().negate(), sender.getBalance(), "Internal", "Completed");
        createStatementLine(receiver, receiver.getProductKey(), "Internal Transfer from " + sender.getDisplayName(),
                receiver.getDisplayName(), "Deposit", request.getAmount(), receiver.getBalance(), "Internal", "Completed");

        transfer.setStatus(TransferStatus.SUCCESS);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.SUCCESS, "Transfer complete. Ledger entries written.");

        notificationService.recordTransfer(
                sender.getUser().getAccountNumber(),
                transfer.getReference(),
                request.getAmount(),
                receiver.getDisplayName(),
                "Internal transfer");

        return new MessageResponse(true, "Internal transfer successful. Reference: " + transfer.getReference());
    }

    @Transactional
    public MessageResponse performExternalTransfer(ExternalTransferRequest request) {
        // 1. CREATED
        Transfer transfer = new Transfer();
        transfer.setReference("EXT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        try {
            transfer.setTransferType(TransferType.valueOf(request.getTransferType().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return new MessageResponse(false, "Invalid transfer type.");
        }
        
        transfer.setDirection(transfer.getTransferType() == TransferType.SWIFT ? TransferDirection.INTERNATIONAL : TransferDirection.DOMESTIC);
        transfer.setAmount(request.getAmount());
        transfer.setDescription(request.getDescription());
        transfer.setStatus(TransferStatus.CREATED);

        Optional<Account> fromAccountOpt = accountRepository.findByAccountNumber(request.getFromAccountNumber());
        if (fromAccountOpt.isEmpty()) {
            return new MessageResponse(false, "Sender account not found.");
        }
        Account sender = fromAccountOpt.get();

        // AML CHECK
        try {
            amlRiskService.analyzeTransfer(sender.getUser(), request.getAmount(), sender.getAccountNumber());
        } catch (RuntimeException e) {
            transfer.setUser(sender.getUser());
            transfer.setFromAccount(sender);
            transfer.setCurrency(sender.getCurrency());
            transfer = transferRepository.save(transfer);
            logAndFail(transfer, e.getMessage());
            return new MessageResponse(false, e.getMessage());
        }

        transfer.setUser(sender.getUser());
        transfer.setFromAccount(sender);
        transfer.setCurrency(sender.getCurrency());
        transfer.setSourceAccountLabel(sender.getDisplayName());
        transfer.setCounterpartyName(request.getRecipientName());
        transfer.setDisplayStatus(DisplayTransferStatus.Processing);
        
        transfer = transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.CREATED, "External transfer request received.");

        boolean international = transfer.getTransferType() == TransferType.SWIFT
                || transfer.getDirection() == TransferDirection.INTERNATIONAL;
        if (transfer.getTransferType() == TransferType.WIRE || transfer.getTransferType() == TransferType.SWIFT) {
            WireComplianceService.WireHoldDecision hold = wireComplianceService.evaluateWireTransfer(
                    sender.getUser(), sender, request.getAmount(), transfer.getTransferType(), international);
            if (hold.isHold()) {
                return placeWireOnComplianceHold(transfer, sender, request, hold);
            }
        }

        // 2. VALIDATION
        if (sender.getStatus() != AccountStatus.ACTIVE) {
            logAndFail(transfer, "Sender account is not ACTIVE.");
            return new MessageResponse(false, "Sender account is frozen or closed.");
        }

        // Fetch Payment Rail Rules
        Optional<PaymentRail> railOpt = paymentRailRepository.findByRailType(PaymentRailType.valueOf(request.getTransferType().toUpperCase()));
        if (railOpt.isEmpty() || !railOpt.get().getActive()) {
            logAndFail(transfer, "Payment rail unavailable.");
            return new MessageResponse(false, "Payment rail unavailable.");
        }
        PaymentRail rail = railOpt.get();

        // Calculate Fees
        BigDecimal feeAmount = rail.getFeeFixed().add(request.getAmount().multiply(rail.getFeePercentage()));
        BigDecimal totalDeduction = request.getAmount().add(feeAmount);

        if (sender.getAvailableBalance().compareTo(totalDeduction) < 0) {
            logAndFail(transfer, "Insufficient funds to cover amount and fees (" + feeAmount + " NGN).");
            return new MessageResponse(false, "Insufficient funds to cover amount and fees.");
        }

        if (request.getAmount().compareTo(sender.getDailyTransferLimit()) > 0) {
            logAndFail(transfer, "Amount exceeds daily transfer limit.");
            return new MessageResponse(false, "Amount exceeds daily transfer limit.");
        }

        transfer.setStatus(TransferStatus.VALIDATED);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.VALIDATED, "Balances, limits, and fees (" + feeAmount + ") verified.");

        // 3. PENDING PROCESSING & DOUBLE ENTRY SYSTEM ACCOUNTS
        transfer.setStatus(TransferStatus.PENDING_PROCESSING);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.PENDING_PROCESSING, "Deducting funds and routing to system accounts.");

        // Deduct from Sender
        sender.setBalance(sender.getBalance().subtract(totalDeduction));
        sender.setAvailableBalance(sender.getAvailableBalance().subtract(totalDeduction));
        accountRepository.save(sender);
        createLedgerEntry(sender, transfer, EntryType.DEBIT, totalDeduction, sender.getBalance());
        createStatementLine(sender, sender.getProductKey(), request.getDescription(),
                sender.getDisplayName(), "Withdrawal", totalDeduction.negate(), sender.getBalance(), request.getTransferType(), "Pending");

        // Credit to System Fee Revenue Account
        Account feeAccount = accountRepository.findByAccountNumber("FEE_REVENUE_01").orElseThrow();
        feeAccount.setBalance(feeAccount.getBalance().add(feeAmount));
        feeAccount.setAvailableBalance(feeAccount.getAvailableBalance().add(feeAmount));
        accountRepository.save(feeAccount);
        createLedgerEntry(feeAccount, transfer, EntryType.CREDIT, feeAmount, feeAccount.getBalance());

        // Credit to System Outbound Suspense Account (waiting to leave bank)
        Account suspenseAccount = accountRepository.findByAccountNumber("OUTBOUND_SUSPENSE_01").orElseThrow();
        suspenseAccount.setBalance(suspenseAccount.getBalance().add(request.getAmount()));
        suspenseAccount.setAvailableBalance(suspenseAccount.getAvailableBalance().add(request.getAmount()));
        accountRepository.save(suspenseAccount);
        createLedgerEntry(suspenseAccount, transfer, EntryType.CREDIT, request.getAmount(), suspenseAccount.getBalance());

        // 4. Save Metadata
        TransferDetail details = new TransferDetail();
        details.setTransfer(transfer);
        details.setBankName(request.getBankName());
        details.setRoutingNumber(request.getRoutingNumber());
        details.setSwiftCode(request.getSwiftCode());
        details.setIban(request.getIban());
        details.setRecipientAccount(request.getRecipientAccount());
        details.setRecipientName(request.getRecipientName());
        details.setRecipientAddress(request.getRecipientAddress());
        transferDetailRepository.save(details);

        TransferFee feeRecord = new TransferFee();
        feeRecord.setTransfer(transfer);
        feeRecord.setFeeAmount(feeAmount);
        feeRecord.setFeeType(rail.getFeePercentage().compareTo(BigDecimal.ZERO) > 0 ? FeeType.PERCENTAGE : FeeType.FLAT);
        feeRecord.setChargedTo(FeeChargedTo.SENDER);
        transferFeeRepository.save(feeRecord);

        // 5. SENT TO RAIL (Simulating external processing)
        transfer.setStatus(TransferStatus.SENT_TO_RAIL);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.SENT_TO_RAIL, "Funds routed to " + rail.getRailType() + " network. Processing time: " + rail.getProcessingTime());

        notificationService.recordTransfer(
                sender.getUser().getAccountNumber(),
                transfer.getReference(),
                request.getAmount(),
                request.getRecipientName(),
                rail.getRailType() + " transfer");

        return new MessageResponse(true, "External transfer submitted. Reference: " + transfer.getReference() + ". Fee charged: " + feeAmount);
    }

    private void createLedgerEntry(Account account, Transfer transfer, EntryType type, BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccount(account);
        entry.setTransfer(transfer);
        entry.setEntryType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        ledgerEntryRepository.save(entry);
    }

    private MessageResponse placeWireOnComplianceHold(Transfer transfer, Account sender,
                                                      ExternalTransferRequest request,
                                                      WireComplianceService.WireHoldDecision hold) {
        transfer.setDisplayStatus(DisplayTransferStatus.Compliance_Hold);
        transfer.setPendingApproval(true);
        transfer.setRiskScore(88);
        transfer.setRiskLevel("High");
        transfer.setStatus(TransferStatus.FAILED);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.FAILED,
                "Compliance hold — funds not released. " + String.join(" ", hold.getReasons()));

        TransferDetail details = new TransferDetail();
        details.setTransfer(transfer);
        details.setBankName(request.getBankName());
        details.setRoutingNumber(request.getRoutingNumber());
        details.setSwiftCode(request.getSwiftCode());
        details.setIban(request.getIban());
        details.setRecipientAccount(request.getRecipientAccount());
        details.setRecipientName(request.getRecipientName());
        details.setRecipientAddress(request.getRecipientAddress());
        transferDetailRepository.save(details);

        String rail = transfer.getTransferType() == TransferType.SWIFT ? "SWIFT wire" : "Fedwire";
        notificationService.recordWireComplianceHold(
                sender.getUser().getAccountNumber(),
                transfer.getReference(),
                request.getAmount(),
                request.getRecipientName(),
                rail,
                hold.getReasons());

        return new MessageResponse(false, hold.getSummaryMessage(), true, transfer.getReference());
    }

    private void logAndFail(Transfer transfer, String message) {
        transfer.setStatus(TransferStatus.FAILED);
        transferRepository.save(transfer);
        logStatus(transfer, TransferStatus.FAILED, message);
    }

    private void logStatus(Transfer transfer, TransferStatus status, String message) {
        TransferStatusLog log = new TransferStatusLog();
        log.setTransfer(transfer);
        log.setStatus(status);
        log.setMessage(message);
        transferStatusLogRepository.save(log);
    }

    private void createStatementLine(Account account, ProductKey key, String desc, String source, String type,
                                     BigDecimal amount, BigDecimal balanceAfter, String channel, String status) {
        StatementLine line = new StatementLine();
        line.setUser(account.getUser());
        line.setProductKey(key);
        line.setLineDate(LocalDate.now());
        line.setDescription(desc);
        line.setSource(source);
        line.setLineType(type);
        line.setAmount(amount);
        line.setBalanceAfter(balanceAfter);
        line.setStatus(status);
        line.setChannel(channel);
        statementLineRepository.save(line);
    }

    public List<LedgerHistoryResponse> getAccountHistory(String accountNumber) {
        Optional<Account> accountOpt = accountRepository.findByAccountNumber(accountNumber);
        if (accountOpt.isEmpty()) {
            throw new IllegalArgumentException("Account not found");
        }

        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountOrderByCreatedAtDesc(accountOpt.get());

        return entries.stream().map(entry -> {
            LedgerHistoryResponse response = new LedgerHistoryResponse();
            response.setTransactionId(entry.getId().toString());
            response.setReference(entry.getTransfer() != null ? entry.getTransfer().getReference() : "N/A");
            response.setEntryType(entry.getEntryType());
            response.setAmount(entry.getAmount());
            response.setBalanceAfter(entry.getBalanceAfter());
            response.setDate(entry.getCreatedAt());
            response.setDescription(entry.getTransfer() != null ? entry.getTransfer().getDescription() : "");
            return response;
        }).collect(Collectors.toList());
    }

    public MessageResponse verifyLedgerBalance(String accountNumber) {
        Optional<Account> accountOpt = accountRepository.findByAccountNumber(accountNumber);
        if (accountOpt.isEmpty()) {
            return new MessageResponse(false, "Account not found");
        }

        Account account = accountOpt.get();
        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountOrderByCreatedAtDesc(account);

        BigDecimal reconstructedBalance = BigDecimal.ZERO;
        
        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() == EntryType.CREDIT) {
                reconstructedBalance = reconstructedBalance.add(entry.getAmount());
            } else if (entry.getEntryType() == EntryType.DEBIT) {
                reconstructedBalance = reconstructedBalance.subtract(entry.getAmount());
            }
        }

        // We removed the hardcoded +50,000 NGN because AuthenticationService now correctly creates a CREDIT ledger entry on signup!
        if (account.getBalance().compareTo(reconstructedBalance) == 0) {
            return new MessageResponse(true, "Ledger verified. Database balance (" + account.getBalance() + ") matches immutable ledger perfectly.");
        } else {
            return new MessageResponse(false, "LEDGER MISMATCH! DB Balance: " + account.getBalance() + " | Reconstructed: " + reconstructedBalance);
        }
    }
}
