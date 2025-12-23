package com.smartsplitpro.controller;

import com.smartsplitpro.service.TransactionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.smartsplitpro.model.Transaction;
import com.smartsplitpro.model.Balance;

@Controller
public class HomeController {
    private final TransactionService transactionService;

    public HomeController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping({"/", "/index"})
    public String index(Model model, jakarta.servlet.http.HttpServletResponse response) {
        try {
            // Prevent caching to avoid stale meta/user after login switches
            try {
                response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
            } catch (Exception ignored) {}
            // Get authenticated user and pass ONLY their per-user balances
            String user = null;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) user = auth.getName();
            } catch (Exception ignored) {}
            
            // Pass per-user balances only (empty list for new users)
            model.addAttribute("balances", transactionService.computeBalancesForUser(user));
            model.addAttribute("currentUser", user);
            boolean isAdmin = false;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) {
                    isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority() != null && a.getAuthority().contains("ADMIN"));
                }
            } catch (Exception ignored) {}
            model.addAttribute("isAdmin", isAdmin);
            return "index";
        } catch (Exception e) {
            // Log and show friendly error page instead of Whitelabel
            e.printStackTrace();
            model.addAttribute("message", "Failed to load dashboard: " + e.getMessage());
            return "error";
        }
    }

    // API to provide balances as JSON for charts
    // Returns ALL global balances for the balance pie chart visualization
    @GetMapping("/api/balances")
    @ResponseBody
    public List<Balance> apiBalances() {
        // Return all global balances for the pie chart
        // This shows everyone's balance in the system
        return transactionService.computeBalances();
    }

    // Lightweight whoami endpoint so front-end can validate the authenticated user
    @GetMapping("/api/whoami")
    @ResponseBody
    public java.util.Map<String,Object> whoami() {
        String user = null;
        boolean isAdmin = false;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                user = auth.getName();
                isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority() != null && a.getAuthority().contains("ADMIN"));
            }
        } catch (Exception ignored) {}
        java.util.Map<String,Object> out = new java.util.HashMap<>();
        out.put("username", user);
        out.put("isAdmin", isAdmin);
        return out;
    }

    // API to provide transactions as JSON for export and graphs
    // Returns ALL transactions in the system
    @GetMapping("/api/transactions")
    @ResponseBody
    public List<Transaction> apiTransactions() {
        // Return all global transactions
        return transactionService.listAll();
    }

    // API to create a transaction via AJAX (JSON)
    @PostMapping("/api/transactions")
    @ResponseBody
    public Transaction createTransaction(@org.springframework.web.bind.annotation.RequestBody Transaction tx) {
        if (tx.getTimestamp() == null) tx.setTimestamp(java.time.LocalDateTime.now());
        if (tx.getAmount() == null) tx.setAmount(java.math.BigDecimal.ZERO);
        try {
            String user = null;
            try { org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(); if (auth!=null) user = auth.getName(); } catch (Exception ignored) {}
            System.out.println("[DEBUG] createTransaction invoked by=" + user + " payload payer=" + tx.getPayerUsername() + " payee=" + tx.getPayeeUsername() + " amt=" + tx.getAmount());
        } catch (Exception ignored) {}
        return transactionService.addTransaction(tx);
    }

    // Trigger optimization (returns instructions)
    @GetMapping("/optimize")
    @ResponseBody
    public List<String> optimize() {
        return transactionService.optimizeSettlements();
    }

    @GetMapping("/api/optimize/me")
    @ResponseBody
    public java.util.List<String> optimizeMe() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        return transactionService.optimizeSettlementsForUser(user);
    }

    // Placeholder settle action: in this scaffold we just return same instructions
    @PostMapping("/settle")
    @ResponseBody
    public Map<String,Object> settle() {
        List<String> instr = transactionService.optimizeSettlements();
        Map<String,Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("instructions", instr);
        return resp;
    }

    // Structured settlements for visualization
    // Returns ALL settlements (global network) for the settlement graph visualization
    // The graph shows the optimal settlement flow between all users
    @GetMapping("/api/settlements")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.Settlement> apiSettlements() {
        // Return all global settlements for the network graph
        // This shows the complete settlement network, not just the current user
        return transactionService.computeSettlements();
    }

    // Per-user settlements for private personal graph (only edges involving the user)
    @GetMapping("/api/settlements/me")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.Settlement> apiSettlementsMe() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}

        if (user == null) return java.util.Collections.emptyList();
        return transactionService.computeSettlementsForUserView(user);
    }

    // Per-user balances endpoint
    @GetMapping("/api/balances/me")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.Balance> apiBalancesMe() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        if (user == null) return java.util.Collections.emptyList();
        return transactionService.computeBalancesForUser(user);
    }

    // Per-user transactions endpoint
    @GetMapping("/api/transactions/me")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.Transaction> apiTransactionsMe() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        if (user == null) return java.util.Collections.emptyList();
        java.util.List<com.smartsplitpro.model.Transaction> out = transactionService.listByUser(user);
        try { System.out.println("[DEBUG] /api/transactions/me user=" + user + " count=" + (out==null?0:out.size())); } catch (Exception ignored) {}
        return out;
    }

    // Permanently delete a transaction by id (used by dashboard delete action)
    @DeleteMapping("/api/transactions/{id}")
    @ResponseBody
    public java.util.Map<String,String> deleteTransaction(@PathVariable Long id) {
        transactionService.deleteTransaction(id);
        java.util.Map<String,String> resp = new java.util.HashMap<>();
        resp.put("status","ok");
        return resp;
    }

    // Transaction history (audit) - includes created/deleted snapshots
    // Returns ONLY history for transactions involving the authenticated user
    @GetMapping("/api/transactions/history")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.TransactionHistory> transactionHistory() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        
        // Get all history entries and filter to only those involving the current user
        java.util.List<com.smartsplitpro.model.TransactionHistory> all = transactionService.listHistory();
        java.util.List<com.smartsplitpro.model.TransactionHistory> userHistory = new java.util.ArrayList<>();
        
        if (user != null && all != null) {
            for (com.smartsplitpro.model.TransactionHistory h : all) {
                if (h == null || h.getPayload() == null) continue;
                // Only include entries where current user is the performer or is involved in the transaction
                if (user.equals(h.getPerformedBy())) {
                    userHistory.add(h);
                } else {
                    // Check if this transaction involves the current user
                    try {
                        String payload = h.getPayload();
                        if (payload.contains("\"" + user + "\"")) {
                            userHistory.add(h);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return userHistory;
    }

    // Save a personal settlement snapshot for the currently authenticated user
    @PostMapping("/api/personal-settlement")
    @ResponseBody
    public java.util.Map<String,Object> savePersonalSettlement() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}

        com.smartsplitpro.model.TransactionHistory saved = transactionService.savePersonalSettlementSnapshot(user);
        java.util.Map<String,Object> resp = new java.util.HashMap<>();
        resp.put("status","ok");
        resp.put("id", saved.getId());
        resp.put("snapshot", saved.getPayload());
        return resp;
    }

    // Page to view personal snapshots
    @GetMapping("/personal")
    public String personalPage(Model model) {
        try {
            String user = null;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) user = auth.getName();
            } catch (Exception ignored) {}
            model.addAttribute("title","Personal Settlements");
            model.addAttribute("currentUser", user);
            return "personal";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("message", "Failed to open Personal page: " + e.getMessage());
            return "error";
        }
    }

    // API to fetch personal snapshots for the current user
    @GetMapping("/api/personal")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.TransactionHistory> apiPersonal() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        java.util.List<com.smartsplitpro.model.TransactionHistory> all = transactionService.listHistory();
        java.util.List<com.smartsplitpro.model.TransactionHistory> out = new java.util.ArrayList<>();
        for (com.smartsplitpro.model.TransactionHistory h: all) {
            if ("PERSONAL_SETTLEMENT".equals(h.getAction()) && (user==null || user.equals(h.getPerformedBy()))) {
                out.add(h);
            }
        }
        return out;
    }

    // API to fetch personal unsettled settlements for notifications
    @GetMapping("/api/personal-notifications")
    @ResponseBody
    public java.util.Map<String, Object> apiPersonalNotifications() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}

    java.util.Map<String, java.util.List<com.smartsplitpro.model.PersonalSettlement>> map = transactionService.listPersonalNotifications(user);
        java.util.List<com.smartsplitpro.model.PersonalSettlement> owe = map.getOrDefault("owe", java.util.Collections.emptyList());
        java.util.List<com.smartsplitpro.model.PersonalSettlement> receive = map.getOrDefault("receive", java.util.Collections.emptyList());

        boolean hasNotifyOnly = false;
        for (com.smartsplitpro.model.PersonalSettlement p : owe) if (p.isNotifyOnly()) { hasNotifyOnly = true; break; }
        if (!hasNotifyOnly) for (com.smartsplitpro.model.PersonalSettlement p : receive) if (p.isNotifyOnly()) { hasNotifyOnly = true; break; }

        boolean limitedView = false;
        if (hasNotifyOnly) {
            boolean hasTransactions = false;
            try {
                java.util.List<com.smartsplitpro.model.Transaction> userTxs = transactionService.listByUser(user);
                hasTransactions = userTxs != null && !userTxs.isEmpty();
            } catch (Exception ignored) {}

            boolean hasNonNotifyPersonal = false;
            try {
                java.util.List<com.smartsplitpro.model.PersonalSettlement> personal = transactionService.listPersonalAll(user);
                if (personal != null) {
                    for (com.smartsplitpro.model.PersonalSettlement p : personal) {
                        if (p != null && !p.isNotifyOnly()) { hasNonNotifyPersonal = true; break; }
                    }
                }
            } catch (Exception ignored) {}

            limitedView = !hasTransactions && !hasNonNotifyPersonal;
        }

        java.util.Map<String,Object> resp = new java.util.HashMap<>();
        try {
            System.out.println("[OPTIMIZED NOTIFICATIONS] user=" + user + " optimizedOwes=" + owe.size() + " optimizedReceives=" + receive.size() + " limitedView=" + limitedView);
            System.out.println("[NOTIFICATION TYPE] These are OPTIMIZED settlements (greedy algorithm result), NOT raw transactions");
        } catch (Exception ignored) {}
        resp.put("owe", owe);
        resp.put("receive", receive);
        resp.put("limitedView", limitedView);
        resp.put("isOptimized", true);  // Flag to indicate these are optimized settlements
        return resp;
    }

    // API to fetch all personal settlements (grouping handled client-side)
    @GetMapping("/api/personal-entries")
    @ResponseBody
    public java.util.List<com.smartsplitpro.model.PersonalSettlement> apiPersonalEntries() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        return transactionService.listPersonalAll(user);
    }
    
    // NEW ENDPOINT: Compare raw vs optimized settlements
    @GetMapping("/api/notifications/comparison")
    @ResponseBody
    public java.util.Map<String, Object> apiNotificationsComparison() {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        
        java.util.Map<String, Object> comparison = transactionService.getOptimizedNotificationsWithComparison(user);
        
        try {
            System.out.println("[COMPARISON] user=" + user + 
                " rawTransactions=" + comparison.get("raw_count") + 
                " optimizedSettlements=" + comparison.get("optimized_count") +
                " savings=" + comparison.get("savings") + " transactions");
        } catch (Exception ignored) {}
        
        return comparison;
    }

    // Mark a personal settlement as settled by id (only payer can mark)
    @PostMapping("/api/personal/{id}/settle")
    @ResponseBody
    public java.util.Map<String,Object> apiMarkPersonalSettled(@PathVariable Long id) {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}
        boolean ok = transactionService.markPersonalSettled(id, user);
        java.util.Map<String,Object> resp = new java.util.HashMap<>();
        resp.put("status", ok ? "ok" : "error");
        return resp;
    }

    // Accept a JSON payload to mark a personal settlement as settled when no
    // persisted personal entry id exists (used by optimized notifications)
    @PostMapping("/api/personal/settle")
    @ResponseBody
    public java.util.Map<String,Object> apiMarkPersonalSettledByInfo(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> payload) {
        String user = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        } catch (Exception ignored) {}

        String from = payload.getOrDefault("fromUser", payload.getOrDefault("from", null)) == null ? null : payload.getOrDefault("fromUser", payload.getOrDefault("from", null)).toString();
        String to = payload.getOrDefault("toUser", payload.getOrDefault("to", null)) == null ? null : payload.getOrDefault("toUser", payload.getOrDefault("to", null)).toString();
        java.math.BigDecimal amount = null;
        try {
            Object a = payload.get("amount");
            if (a != null) amount = new java.math.BigDecimal(a.toString());
        } catch (Exception ignored) {}

        java.util.Map<String,Object> resp = new java.util.HashMap<>();

        if (from == null || to == null || amount == null) {
            resp.put("status", "error");
            resp.put("message", "Missing fromUser/toUser/amount in payload");
            return resp;
        }

        try {
            boolean ok = transactionService.createAndMarkPersonalSettled(from, to, amount, user == null ? "unknown" : user);
            if (ok) {
                resp.put("status", "ok");
            } else {
                resp.put("status", "error");
                resp.put("message", "Failed to persist settlement");
            }
            return resp;
        } catch (Exception e) {
            resp.put("status", "error");
            resp.put("message", "Failed to save/settle: " + e.getMessage());
            return resp;
        }
    }

    // Dedicated history page
    @GetMapping("/history")
    public String historyPage(Model model) {
        try {
            // server can supply initial entries if desired; front-end will fetch updates
            model.addAttribute("title", "Transaction History");
            return "history";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("message", "Failed to open History page: " + e.getMessage());
            return "error";
        }
    }
}
