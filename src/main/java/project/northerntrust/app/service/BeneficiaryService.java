package project.northerntrust.app.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.northerntrust.app.dto.BeneficiaryRequest;
import project.northerntrust.app.dto.MessageResponse;
import project.northerntrust.app.entity.Beneficiary;
import project.northerntrust.app.entity.User;
import project.northerntrust.app.entity.enums.*;
import project.northerntrust.app.repository.BeneficiaryRepository;
import project.northerntrust.app.repository.UserRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BeneficiaryService {

    @Autowired
    private BeneficiaryRepository beneficiaryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DashboardService dashboardService;

    public MessageResponse saveBeneficiary(BeneficiaryRequest request) {
        Optional<User> userOpt = userRepository.findByAccountNumber(request.getUserAccountNumber());
        if (userOpt.isEmpty()) {
            return new MessageResponse(false, "User not found.");
        }

        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setUser(userOpt.get());
        beneficiary.setAccountNumber(request.getAccountNumber());
        beneficiary.setBankName(request.getBankName());
        beneficiary.setAccountName(request.getAccountName());
        beneficiary.setNickname(request.getNickname());

        beneficiaryRepository.save(beneficiary);

        return new MessageResponse(true, "Beneficiary saved successfully.");
    }

    public List<Beneficiary> getUserBeneficiaries(String userAccountNumber) {
        Optional<User> userOpt = userRepository.findByAccountNumber(userAccountNumber);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("User not found.");
        }

        return beneficiaryRepository.findByUser(userOpt.get());
    }

    public List<Map<String, Object>> getBeneficiariesV1(String accountNumber, String type, String search) {
        return dashboardService.getBeneficiaries(accountNumber, type, search);
    }

    @Transactional
    public MessageResponse createBeneficiaryV1(String accountNumber, Map<String, Object> body) {
        User user = userRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Beneficiary b = new Beneficiary();
        b.setUser(user);
        b.setBeneficiaryCode("BEN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        b.setBeneficiaryType(BeneficiaryType.valueOf(body.get("type").toString().toUpperCase()));
        b.setDisplayName(body.get("displayName").toString());
        b.setRelationship(body.getOrDefault("relationship", "").toString());
        if (body.containsKey("bankName")) b.setBankName(body.get("bankName").toString());
        if (body.containsKey("accountNumber")) b.setAccountNumber(body.get("accountNumber").toString());
        if (body.containsKey("routingOrSwift")) b.setRoutingOrSwift(body.get("routingOrSwift").toString());
        b.setSingleLimit(new BigDecimal(body.getOrDefault("singleLimit", "100000").toString()));
        b.setDailyLimit(new BigDecimal(body.getOrDefault("dailyLimit", "250000").toString()));
        b.setStatus(BeneficiaryStatus.ACTIVE);
        String trust = body.containsKey("trustLevel") ? body.get("trustLevel").toString()
                : body.getOrDefault("trust", "New").toString();
        if ("Trusted".equalsIgnoreCase(trust)) {
            b.setTrustLevel(TrustLevel.Trusted);
            b.setTrusted(true);
        } else if ("Blocked".equalsIgnoreCase(trust)) {
            b.setTrustLevel(TrustLevel.Blocked);
            b.setStatus(BeneficiaryStatus.BLOCKED);
        } else if ("Verified".equalsIgnoreCase(trust)) {
            b.setTrustLevel(TrustLevel.Verified);
            b.setTrusted(false);
        } else {
            b.setTrustLevel(TrustLevel.New);
            b.setTrusted(false);
        }
        beneficiaryRepository.save(b);
        return new MessageResponse(true, "Beneficiary created: " + b.getBeneficiaryCode());
    }

    @Transactional
    public MessageResponse updateLimits(String accountNumber, String beneficiaryCode, BigDecimal single, BigDecimal daily) {
        User user = userRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Beneficiary b = beneficiaryRepository.findByBeneficiaryCodeAndUser(beneficiaryCode, user)
                .orElseThrow(() -> new IllegalArgumentException("Beneficiary not found"));
        b.setSingleLimit(single);
        b.setDailyLimit(daily);
        beneficiaryRepository.save(b);
        return new MessageResponse(true, "Limits updated");
    }

    @Transactional
    public MessageResponse updateTrust(String accountNumber, String beneficiaryCode, boolean trusted) {
        User user = userRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Beneficiary b = beneficiaryRepository.findByBeneficiaryCodeAndUser(beneficiaryCode, user)
                .orElseThrow(() -> new IllegalArgumentException("Beneficiary not found"));
        b.setTrusted(trusted);
        b.setTrustLevel(trusted ? TrustLevel.Trusted : TrustLevel.Verified);
        beneficiaryRepository.save(b);
        return new MessageResponse(true, "Trust updated");
    }

    @Transactional
    public MessageResponse updateStatus(String accountNumber, String beneficiaryCode, BeneficiaryStatus status) {
        User user = userRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Beneficiary b = beneficiaryRepository.findByBeneficiaryCodeAndUser(beneficiaryCode, user)
                .orElseThrow(() -> new IllegalArgumentException("Beneficiary not found"));
        b.setStatus(status);
        if (status == BeneficiaryStatus.BLOCKED) {
            b.setTrustLevel(TrustLevel.Blocked);
        }
        beneficiaryRepository.save(b);
        return new MessageResponse(true, "Status updated");
    }
}
