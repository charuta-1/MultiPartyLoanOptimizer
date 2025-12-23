package com.smartsplitpro.controller;

import com.smartsplitpro.repository.PersonalSettlementRepository;
import com.smartsplitpro.repository.TransactionRepository;
import com.smartsplitpro.repository.TransactionHistoryRepository;
import com.smartsplitpro.service.TransactionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class DebugController {
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final PersonalSettlementRepository personalSettlementRepository;
    private final TransactionHistoryRepository historyRepository;

    public DebugController(TransactionService transactionService,
                           TransactionRepository transactionRepository,
                           PersonalSettlementRepository personalSettlementRepository,
                           TransactionHistoryRepository historyRepository) {
        this.transactionService = transactionService;
        this.transactionRepository = transactionRepository;
        this.personalSettlementRepository = personalSettlementRepository;
        this.historyRepository = historyRepository;
    }

    @GetMapping("/status")
    @ResponseBody
    public java.util.Map<String, Object> status() {
        String user = null;
        try { org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(); if (auth!=null) user = auth.getName(); } catch (Exception ignored) {}

        java.util.Map<String,Object> out = new java.util.HashMap<>();
        out.put("authUser", user);
        try { out.put("transactionsForUser", transactionService.listByUser(user)); } catch (Exception e) { out.put("transactionsForUser_error", e.getMessage()); }
        try { out.put("personalForUser", transactionService.listPersonalAll(user)); } catch (Exception e) { out.put("personalForUser_error", e.getMessage()); }
        try { out.put("balancesForUser", transactionService.computeBalancesForUser(user)); } catch (Exception e) { out.put("balancesForUser_error", e.getMessage()); }

        // also return recent global counts to compare
        try { out.put("totalTransactions", transactionRepository.count()); } catch (Exception ignored) {}
        try { out.put("totalPersonal", personalSettlementRepository.count()); } catch (Exception ignored) {}

        return out;
    }

    @GetMapping("/reset")
    @ResponseBody
    public java.util.Map<String,Object> reset() {
        java.util.Map<String,Object> out = new java.util.HashMap<>();
        String user = null;
        try { org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(); if (auth!=null) user = auth.getName(); } catch (Exception ignored) {}
        out.put("requestedBy", user);
        // simple auth gate: only allow if logged in user is 'admin'
        if (user == null || !"admin".equals(user)) {
            out.put("status", "forbidden");
            out.put("message", "Reset allowed only for admin user");
            return out;
        }
        try {
            personalSettlementRepository.deleteAll();
            transactionRepository.deleteAll();
            historyRepository.deleteAll();
            out.put("status", "ok");
            out.put("message", "All transactions, personal settlements, and history cleared");
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", e.getMessage());
        }
        return out;
    }
}
