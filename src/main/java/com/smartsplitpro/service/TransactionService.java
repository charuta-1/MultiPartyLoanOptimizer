package com.smartsplitpro.service;

import com.smartsplitpro.model.Balance;
import com.smartsplitpro.model.Transaction;
import com.smartsplitpro.repository.TransactionRepository;
import com.smartsplitpro.repository.TransactionHistoryRepository;
import com.smartsplitpro.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final TransactionHistoryRepository historyRepository;
    private final com.smartsplitpro.repository.PersonalSettlementRepository personalSettlementRepository;
    // userRepository and passwordEncoder intentionally retained in constructor
    // for backward-compatibility with other components but are not required
    // to create placeholder users anymore. Keep them in case other services
    // or future features need them.
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TransactionService(TransactionRepository transactionRepository, UserRepository userRepository, TransactionHistoryRepository historyRepository, com.smartsplitpro.repository.PersonalSettlementRepository personalSettlementRepository, PasswordEncoder passwordEncoder) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.historyRepository = historyRepository;
        this.personalSettlementRepository = personalSettlementRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Transaction addTransaction(Transaction tx) {
        return addTransaction(tx, true);
    }

    /**
     * Persist a transaction and optionally create personal settlement entries.
     * When createPersonalEntries is false the transaction is saved and history recorded
     * but no personal settlement rows are created. This is used when adding an
     * additional participant: we want to notify the added user without importing
     * the original user's full history into that account.
     */
    @Transactional
    public Transaction addTransaction(Transaction tx, boolean createPersonalEntries) {
        // Do not create placeholder User records when saving transactions. Transactions
        // store payer/payee as plain usernames (strings). Creating placeholder users
        // can cause newly-registered accounts to inherit previous history. To keep
        // registered accounts distinct, we only persist the Transaction here.

        // capture authenticated username once so we can fall back if the client omits the payer
        String authUser = null;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) authUser = auth.getName();
        } catch (Exception ignored) {}
        String normalizedAuthUser = authUser == null ? null : authUser.trim().toLowerCase();

        // normalize usernames and timestamp to avoid mismatch between auth username and entered values
        if (tx.getPayerUsername() != null && !tx.getPayerUsername().isBlank()) {
            tx.setPayerUsername(tx.getPayerUsername().trim().toLowerCase());
        } else if (normalizedAuthUser != null && !normalizedAuthUser.isBlank()) {
            // Some browsers submit an empty payer field even though the UI defaults to "you".
            // Default the payer to the authenticated account so the creator immediately sees the row.
            tx.setPayerUsername(normalizedAuthUser);
        }

        if (tx.getPayeeUsername() != null && !tx.getPayeeUsername().isBlank()) {
            tx.setPayeeUsername(tx.getPayeeUsername().trim().toLowerCase());
        }

        if (normalizedAuthUser != null && !normalizedAuthUser.isBlank()) {
            tx.setCreatedBy(normalizedAuthUser);
        }

        if (tx.getTimestamp() == null) tx.setTimestamp(java.time.LocalDateTime.now());

        Transaction saved = transactionRepository.save(tx);
        // Force flush to reduce any lag before subsequent read endpoints see the new row
        try { transactionRepository.flush(); } catch (Exception ignored) {}
        try {
            System.out.println("[DEBUG] transaction count after save=" + transactionRepository.count());
        } catch (Exception ignored) {}
        // record history
        recordHistory(saved, "CREATED");

        try {
            System.out.println("[DEBUG] addTransaction saved id=" + (saved.getId()==null?"null":saved.getId()) + " payer=" + saved.getPayerUsername() + " payee=" + saved.getPayeeUsername() + " amt=" + saved.getAmount() + " createdBy=" + saved.getCreatedBy() + " createPersonal=" + createPersonalEntries + " authUser=" + authUser);
        } catch (Exception ignored) {}

        if (createPersonalEntries) {
            // Automatically create personal settlement entries for both payer and payee
            // so they immediately see the transaction on their dashboard
            try {
                String payer = saved.getPayerUsername();
                String payee = saved.getPayeeUsername();
                BigDecimal amount = saved.getAmount() == null ? BigDecimal.ZERO : saved.getAmount();
                java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());

                if (payer != null && !payer.isBlank() && payee != null && !payee.isBlank()) {
                    boolean payeeRegistered = isRegistered(payee);
                    com.smartsplitpro.model.PersonalSettlement entry =
                            new com.smartsplitpro.model.PersonalSettlement(payer, payee, amount, now, false, saved.getId());
                    entry.setRecipientRegistered(payeeRegistered);
                    personalSettlementRepository.save(entry);
                }
            } catch (Exception e) {
                System.out.println("Failed to create personal settlement entries for transaction: " + e.getMessage());
                // Don't fail the transaction creation if personal entries fail
            }
        }

        return saved;
    }

    public List<Transaction> listAll() {
        List<Transaction> all = transactionRepository.findAll();
        try {
            System.out.println("[DEBUG] listAll count=" + (all == null ? 0 : all.size()));
        } catch (Exception ignored) {}
        return all;
    }

    public List<Transaction> listByUser(String username) {
        if (username == null) return java.util.Collections.emptyList();
        String norm = username.trim().toLowerCase();

        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        java.util.List<Transaction> result = new java.util.ArrayList<>();

        java.util.function.Consumer<java.util.List<Transaction>> merge = list -> {
            if (list == null || list.isEmpty()) return;
            for (Transaction t : list) {
                if (t == null) continue;
                Long id = t.getId();
                if (id != null) {
                    if (seen.add(id)) result.add(t);
                } else {
                    result.add(t);
                }
            }
        };

        try { merge.accept(transactionRepository.findByPayerUsernameOrPayeeUsername(norm, norm)); } catch (Exception ignored) {}
        try { merge.accept(transactionRepository.findByPayerUsernameIgnoreCaseOrPayeeUsernameIgnoreCase(norm, norm)); } catch (Exception ignored) {}
        try { merge.accept(transactionRepository.findByCreatedBy(norm)); } catch (Exception ignored) {}
        try { merge.accept(transactionRepository.findByCreatedByIgnoreCase(norm)); } catch (Exception ignored) {}

        if (result.isEmpty()) {
            // Fallback: fetch all and manually filter when repository methods return nothing
            try {
                java.util.List<Transaction> globals = transactionRepository.findAll();
                if (globals != null) {
                    for (Transaction t : globals) {
                        if (t == null) continue;
                        String payer = t.getPayerUsername() == null ? null : t.getPayerUsername().trim().toLowerCase();
                        String payee = t.getPayeeUsername() == null ? null : t.getPayeeUsername().trim().toLowerCase();
                        String creator = t.getCreatedBy() == null ? null : t.getCreatedBy().trim().toLowerCase();
                        if (norm.equals(payer) || norm.equals(payee) || norm.equals(creator)) {
                            Long id = t.getId();
                            if (id != null) {
                                if (seen.add(id)) result.add(t);
                            } else {
                                result.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        result.removeIf(java.util.Objects::isNull);
        result.removeIf(t -> {
            String creator = t.getCreatedBy() == null ? null : t.getCreatedBy().trim().toLowerCase();
            if (creator != null && !creator.isBlank()) {
                return !creator.equals(norm);
            }
            String payer = t.getPayerUsername() == null ? null : t.getPayerUsername().trim().toLowerCase();
            return payer == null || !payer.equals(norm);
        });

        // MANUAL SORTING: Sort transactions by timestamp (descending - most recent first)
        // Using Bubble Sort for educational purposes (can also use Quick Sort or Merge Sort)
        bubbleSortTransactionsByTimestamp(result);

        try {
            System.out.println("[DEBUG] listByUser(" + norm + ") size=" + result.size());
        } catch (Exception ignored) {}
        return result;
    }

    public Optional<Transaction> findById(Long id) {
        try {
            if (id == null) return Optional.empty();
            return transactionRepository.findById(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<Balance> computeBalances() {
        List<Transaction> txs = transactionRepository.findAll();

        // MANUAL HASH MAP - Using custom implementation instead of built-in HashMap
        SimpleHashMap<String, BigDecimal> map = new SimpleHashMap<>();

        for (Transaction tx : txs) {
            String payer = tx.getPayerUsername();
            String payee = tx.getPayeeUsername();
            BigDecimal amt = tx.getAmount() == null ? BigDecimal.ZERO : tx.getAmount();

            if (payer == null || payer.isBlank()) continue;
            if (payee == null || payee.isBlank()) continue;

            // payer paid amount; payee owes that amount (for simplicity)
            BigDecimal payerBalance = map.get(payer);
            if (payerBalance == null) payerBalance = BigDecimal.ZERO;
            map.put(payer, payerBalance.add(amt));

            BigDecimal payeeBalance = map.get(payee);
            if (payeeBalance == null) payeeBalance = BigDecimal.ZERO;
            map.put(payee, payeeBalance.subtract(amt));
        }

        try {
            System.out.println("[DEBUG] computeBalances totals=" + map);
        } catch (Exception ignored) {}

        // Convert manual hash map to List<Balance>
        List<Balance> result = new ArrayList<>();
        for (String key : map.keys()) {
            result.add(new Balance(key, map.get(key)));
        }
        return result;
    }

    /**
     * Compute balances limited to transactions that involve the specified username.
     * This returns a list of Balance objects for the user and any counterparties
     * they've transacted with. Useful for powering per-user views without exposing
     * global balances.
     */
    public List<Balance> computeBalancesForUser(String username) {
        if (username == null) return java.util.Collections.emptyList();
        String norm = username.trim().toLowerCase();
        List<Transaction> txs = listByUser(username);
        if (txs == null || txs.isEmpty()) return java.util.Collections.emptyList();

        // MANUAL HASH MAP - Using custom implementation
        SimpleHashMap<String, BigDecimal> map = new SimpleHashMap<>();

        for (Transaction tx : txs) {
            if (tx == null) continue;
            String payer = tx.getPayerUsername();
            String payee = tx.getPayeeUsername();
            BigDecimal amt = tx.getAmount() == null ? BigDecimal.ZERO : tx.getAmount();

            if (payer == null || payee == null) continue;

            // payer paid amount; payee owes that amount (for simplicity)
            BigDecimal payerBalance = map.get(payer);
            if (payerBalance == null) payerBalance = BigDecimal.ZERO;
            map.put(payer, payerBalance.add(amt));

            BigDecimal payeeBalance = map.get(payee);
            if (payeeBalance == null) payeeBalance = BigDecimal.ZERO;
            map.put(payee, payeeBalance.subtract(amt));
        }

        if (map.size() == 0) return java.util.Collections.emptyList();

        try {
            System.out.println("[DEBUG] computeBalancesForUser(" + norm + ")=" + map);
        } catch (Exception ignored) {}

        // Convert manual hash map to List<Balance>
        List<Balance> result = new ArrayList<>();
        for (String key : map.keys()) {
            result.add(new Balance(key, map.get(key)));
        }
        return result;
    }

    @SuppressWarnings("unused")
    private List<Transaction> buildTransactionsFromPersonal(String username) {
        if (username == null) return java.util.Collections.emptyList();
        java.util.List<com.smartsplitpro.model.PersonalSettlement> personal;
        try {
            personal = personalSettlementRepository.findByFromUserOrToUserOrderByCreatedAtDesc(username, username);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }

        if (personal == null || personal.isEmpty()) return java.util.Collections.emptyList();

        Map<String, Transaction> unique = new LinkedHashMap<>();
        for (com.smartsplitpro.model.PersonalSettlement entry : personal) {
            if (entry == null || entry.isSettled()) continue;
            String fromUser = entry.getFromUser();
            String toUser = entry.getToUser();
            java.math.BigDecimal amount = entry.getAmount() == null ? java.math.BigDecimal.ZERO : entry.getAmount();
            String key = (fromUser == null ? "" : fromUser) + "->" + (toUser == null ? "" : toUser) + "#" + amount.toPlainString();
            if (unique.containsKey(key)) continue;

            Transaction pseudo = new Transaction();
            if (entry.getId() != null) pseudo.setId(-Math.abs(entry.getId()));
            pseudo.setPayerUsername(fromUser);
            pseudo.setPayeeUsername(toUser);
            pseudo.setAmount(amount);
            if (entry.getCreatedAt() != null) pseudo.setTimestamp(entry.getCreatedAt().toLocalDateTime());
            // omit description so dashboard shows a clean row
            unique.put(key, pseudo);
        }

        return new java.util.ArrayList<>(unique.values());
    }

    public List<com.smartsplitpro.model.Settlement> computeSettlementsForUserView(String username) {
        if (username == null) return java.util.Collections.emptyList();
        List<com.smartsplitpro.model.Settlement> all = computeSettlements();
        List<com.smartsplitpro.model.Settlement> userEdges = new java.util.ArrayList<>();
        if (all != null) {
            for (com.smartsplitpro.model.Settlement s : all) {
                if (s == null) continue;
                if (username.equals(s.getFrom()) || username.equals(s.getTo())) {
                    userEdges.add(s);
                }
            }
        }
        // Do NOT fall back to personal settlements to fabricate a network.
        // Only show network edges derived from real transactions. If none, return empty.
        return userEdges;
    }

    public List<String> optimizeSettlementsForUser(String username) {
        if (username == null) return java.util.Collections.emptyList();
        List<com.smartsplitpro.model.Settlement> edges = computeSettlementsForUserView(username);
        if (edges == null || edges.isEmpty()) return java.util.Collections.emptyList();

        java.text.NumberFormat fmt = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US);
        List<String> instructions = new java.util.ArrayList<>();
        for (com.smartsplitpro.model.Settlement s : edges) {
            if (s == null || s.getAmount() == null) continue;
            String amount = fmt.format(s.getAmount());
            if (username.equals(s.getFrom())) {
                instructions.add("Pay " + amount + " to " + s.getTo());
            } else if (username.equals(s.getTo())) {
                instructions.add(s.getFrom() + " should pay you " + amount);
            }
        }
        return instructions;
    }

    public List<String> optimizeSettlements() {
       
        List<Balance> balances = computeBalances();

        // MANUAL IMPLEMENTATION: Separate positives and negatives
        List<Balance> positives = new ArrayList<>();
        List<Balance> negatives = new ArrayList<>();
        
        for (Balance b : balances) {
            if (b.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                positives.add(b);
            } else if (b.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                negatives.add(b);
            }
        }
        
        // MANUAL QUICK SORT: Sort positives in descending order
        quickSortBalancesDescending(positives, 0, positives.size() - 1);
        
        // MANUAL QUICK SORT: Sort negatives in ascending order (most negative first)
        quickSortBalancesAscending(negatives, 0, negatives.size() - 1);

        List<String> instructions = new ArrayList<>();

        int i = 0, j = 0;
        while (i < positives.size() && j < negatives.size()) {
            Balance pos = positives.get(i);
            Balance neg = negatives.get(j);
            BigDecimal owe = pos.getBalance().min(neg.getBalance().abs());

            instructions.add(String.format("%s receives %s from %s", pos.getUsername(), owe, neg.getUsername()));

            pos.setBalance(pos.getBalance().subtract(owe));
            neg.setBalance(neg.getBalance().add(owe));

            if (pos.getBalance().compareTo(BigDecimal.ZERO) == 0) i++;
            if (neg.getBalance().compareTo(BigDecimal.ZERO) == 0) j++;
        }

        return instructions;
    }

    // Compute structured settlements (from -> to -> amount) to drive a network graph
    public List<com.smartsplitpro.model.Settlement> computeSettlements() {
        List<Balance> balances = computeBalances();

        // MANUAL IMPLEMENTATION: Separate positives and negatives
        List<Balance> positives = new ArrayList<>();
        List<Balance> negatives = new ArrayList<>();
        
        for (Balance b : balances) {
            if (b.getBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
                positives.add(b);
            } else if (b.getBalance().compareTo(java.math.BigDecimal.ZERO) < 0) {
                negatives.add(b);
            }
        }
        
        // MANUAL QUICK SORT: Sort using manual implementation
        quickSortBalancesDescending(positives, 0, positives.size() - 1);
        quickSortBalancesAscending(negatives, 0, negatives.size() - 1);

        List<com.smartsplitpro.model.Settlement> edges = new ArrayList<>();

        int i = 0, j = 0;
        while (i < positives.size() && j < negatives.size()) {
            Balance pos = positives.get(i);
            Balance neg = negatives.get(j);
            java.math.BigDecimal owe = pos.getBalance().min(neg.getBalance().abs());

            // neg owes owe to pos
            edges.add(new com.smartsplitpro.model.Settlement(neg.getUsername(), pos.getUsername(), owe));

            pos.setBalance(pos.getBalance().subtract(owe));
            neg.setBalance(neg.getBalance().add(owe));

            if (pos.getBalance().compareTo(java.math.BigDecimal.ZERO) == 0) i++;
            if (neg.getBalance().compareTo(java.math.BigDecimal.ZERO) == 0) j++;
        }

        return edges;
    }

    @Transactional
    public void deleteTransaction(Long id) {
        if (id == null) return;
        // record the transaction payload before deletion if exists
        Optional<Transaction> ex = transactionRepository.findById(id);
        ex.ifPresent(t -> recordHistory(t, "DELETED"));
        // let repository throw an exception if id doesn't exist; transaction ensures consistency
        transactionRepository.deleteById(id);
        try {
            personalSettlementRepository.deleteAllByTransactionId(id);
        } catch (Exception e) {
            System.out.println("Failed to prune personal settlements for transaction " + id + ": " + e.getMessage());
        }
    }

    private void recordHistory(Transaction tx, String action) {
        try {
            // include the original transaction timestamp plus a recordedAt timestamp with offset
            java.time.OffsetDateTime recordedAt = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());
            String txTs = tx.getTimestamp() == null ? null : tx.getTimestamp().toString();
        String payload = String.format(
            "{\"id\":%s,\"payerUsername\":\"%s\",\"payeeUsername\":\"%s\",\"amount\":%s,\"description\":\"%s\",\"timestamp\":%s,\"createdBy\":\"%s\",\"recordedAt\":\"%s\"}",
                    tx.getId()==null?"null":tx.getId().toString(),
                    tx.getPayerUsername()==null?"":tx.getPayerUsername(),
                    tx.getPayeeUsername()==null?"":tx.getPayeeUsername(),
                    tx.getAmount()==null?"0":tx.getAmount().toString(),
                    tx.getDescription()==null?"":tx.getDescription().replace("\"","\\\""),
                    txTs==null?"null":"\""+txTs+"\"",
            tx.getCreatedBy()==null?"":tx.getCreatedBy(),
                    recordedAt.toString());

            String performedBy = null;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) performedBy = auth.getName();
            } catch (Exception ignored) {}

            com.smartsplitpro.model.TransactionHistory h = new com.smartsplitpro.model.TransactionHistory(tx.getId(), action, payload, performedBy, recordedAt);
            historyRepository.save(h);
        } catch (Exception e) {
            // swallow to avoid failing main operation; log to stdout
            System.out.println("Failed to record history: " + e.getMessage());
        }
    }

    public java.util.List<com.smartsplitpro.model.TransactionHistory> listHistory() {
        return historyRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
    }

    @Transactional
    public com.smartsplitpro.model.TransactionHistory savePersonalSettlementSnapshot(String username) {
        return savePersonalSettlementSnapshot(username, false);
    }

    @Transactional
    public com.smartsplitpro.model.TransactionHistory savePersonalSettlementSnapshot(String username, boolean notifyOnly) {
        // compute structured settlements
        List<com.smartsplitpro.model.Settlement> edges = computeSettlements();

        // filter edges that involve the user
        List<com.smartsplitpro.model.Settlement> personal = new ArrayList<>();
        java.math.BigDecimal totalGive = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalReceive = java.math.BigDecimal.ZERO;
        for (com.smartsplitpro.model.Settlement s : edges) {
            if (username == null) continue;
            if (username.equals(s.getFrom())) {
                personal.add(s);
                totalGive = totalGive.add(s.getAmount()==null?java.math.BigDecimal.ZERO:s.getAmount());
            } else if (username.equals(s.getTo())) {
                personal.add(s);
                totalReceive = totalReceive.add(s.getAmount()==null?java.math.BigDecimal.ZERO:s.getAmount());
            }
        }

        // build payload JSON
    java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"snapshotAt\":\"").append(now.toString()).append("\",");
        sb.append("\"username\":\"").append(username==null?"":username).append("\",");
        sb.append("\"totalGive\":").append(totalGive.toString()).append(',');
        sb.append("\"totalReceive\":").append(totalReceive.toString()).append(',');
        sb.append("\"entries\":[");
        for (int i=0;i<personal.size();i++) {
            com.smartsplitpro.model.Settlement s = personal.get(i);
            sb.append('{');
            sb.append("\"from\":\"").append(s.getFrom()).append("\",");
            sb.append("\"to\":\"").append(s.getTo()).append("\",");
            sb.append("\"amount\":").append(s.getAmount()==null?"0":s.getAmount().toString());
            sb.append('}');
            if (i < personal.size()-1) sb.append(',');
        }
        sb.append(']');
        sb.append('}');

        String payload = sb.toString();
        com.smartsplitpro.model.TransactionHistory h = new com.smartsplitpro.model.TransactionHistory(null, "PERSONAL_SETTLEMENT", payload, username, now);
        com.smartsplitpro.model.TransactionHistory saved = historyRepository.save(h);

        // Also persist individual personal settlement entries for notifications
        try {
            for (com.smartsplitpro.model.Settlement s : personal) {
                // we only saved settlements that involved the user earlier; ensure we also persist entries
                com.smartsplitpro.model.Settlement entry = s;
                if (entry.getFrom() != null && entry.getTo() != null && entry.getAmount() != null) {
                    com.smartsplitpro.model.PersonalSettlement ps = new com.smartsplitpro.model.PersonalSettlement(entry.getFrom(), entry.getTo(), entry.getAmount(), now, notifyOnly);
                    ps.setRecipientRegistered(isRegistered(entry.getTo()));
                    personalSettlementRepository.save(ps);
                }
            }
        } catch (Exception ex) {
            System.out.println("Failed to save personal settlement entries: " + ex.getMessage());
        }

        return saved;
    }

    /**
     * Create a single notify-only personal settlement entry for a specific transaction
     * This avoids creating a full snapshot and therefore prevents mixing global history into the new user's view.
     */
    @Transactional
    public com.smartsplitpro.model.PersonalSettlement createNotifyOnlyPersonalEntry(String fromUser, String toUser, java.math.BigDecimal amount) {
        return createNotifyOnlyPersonalEntry(fromUser, toUser, amount, null);
    }

    @Transactional
    public com.smartsplitpro.model.PersonalSettlement createNotifyOnlyPersonalEntry(String fromUser, String toUser, java.math.BigDecimal amount, Long transactionId) {
        try {
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());
            com.smartsplitpro.model.PersonalSettlement ps = new com.smartsplitpro.model.PersonalSettlement(fromUser, toUser, amount == null ? java.math.BigDecimal.ZERO : amount, now, true, transactionId);
            ps.setRecipientRegistered(isRegistered(toUser));
            return personalSettlementRepository.save(ps);
        } catch (Exception e) {
            System.out.println("Failed to create notify-only personal entry: " + e.getMessage());
            return null;
        }
    }

    // Return unsettled personal settlements where current user is the payer (fromUser)
    public java.util.List<com.smartsplitpro.model.PersonalSettlement> listPersonalUnsettled(String username) {
        if (username == null) return java.util.Collections.emptyList();
        return personalSettlementRepository.findByFromUserAndSettledFalseOrderByCreatedAtDesc(username);
    }

    // Return OPTIMIZED settlements as notifications (not raw transactions)
    // This shows the user WHAT THEY SHOULD DO based on greedy optimization
    // NOT what they already know from transactions
    // 
    // Example: If user has transactions with A, B, C
    // Instead of showing: "You paid A $50", "B paid you $30", "You paid C $20"
    // Show optimized: "Pay A $40 (this settles everything with A, B, C)"
    public java.util.Map<String, java.util.List<com.smartsplitpro.model.PersonalSettlement>> listPersonalNotifications(String username) {
        java.util.Map<String, java.util.List<com.smartsplitpro.model.PersonalSettlement>> result = new java.util.HashMap<>();
        if (username == null) {
            result.put("owe", java.util.Collections.emptyList());
            result.put("receive", java.util.Collections.emptyList());
            return result;
        }

        // STEP 1: Run greedy optimization to get optimal settlements
        List<com.smartsplitpro.model.Settlement> optimizedSettlements = computeSettlements();
        
        // STEP 2: Get already settled entries from database to exclude them
        java.util.List<com.smartsplitpro.model.PersonalSettlement> settledEntries = 
            personalSettlementRepository.findByFromUserOrToUserOrderByCreatedAtDesc(username, username)
                .stream()
                .filter(ps -> ps != null && ps.isSettled())
                .collect(java.util.stream.Collectors.toList());
        
        // STEP 3: Filter to only settlements involving this user (and not already settled)
        java.util.List<com.smartsplitpro.model.PersonalSettlement> owe = new java.util.ArrayList<>();
        java.util.List<com.smartsplitpro.model.PersonalSettlement> receive = new java.util.ArrayList<>();
        
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());
        
        for (com.smartsplitpro.model.Settlement settlement : optimizedSettlements) {
            String from = settlement.getFrom();
            String to = settlement.getTo();
            java.math.BigDecimal amount = settlement.getAmount();
            
            // Check if this exact settlement was already marked as settled
            boolean alreadySettled = settledEntries.stream().anyMatch(ps -> 
                ps.getFromUser().equalsIgnoreCase(from) && 
                ps.getToUser().equalsIgnoreCase(to) &&
                ps.getAmount().compareTo(amount) == 0
            );
            
            if (alreadySettled) {
                continue; // Skip this settlement, it's already done
            }
            
            // Check if this settlement involves the current user
            if (username.equalsIgnoreCase(from)) {
                // User OWES money (user is the payer)
                com.smartsplitpro.model.PersonalSettlement ps = 
                    new com.smartsplitpro.model.PersonalSettlement(from, to, amount, now, false);
                ps.setRecipientRegistered(isRegistered(to));
                owe.add(ps);
            } else if (username.equalsIgnoreCase(to)) {
                // User should RECEIVE money (user is the payee)
                com.smartsplitpro.model.PersonalSettlement ps = 
                    new com.smartsplitpro.model.PersonalSettlement(from, to, amount, now, false);
                ps.setRecipientRegistered(isRegistered(from));
                receive.add(ps);
            }
        }
        
        // STEP 3: Attach contact details (phone numbers)
        attachContactDetails(owe);
        attachContactDetails(receive);
        
        // STEP 4: Filter out if recipient not registered (optional - for receive only)
        receive.removeIf(ps -> ps == null || !ps.isRecipientRegistered());

        result.put("owe", owe);
        result.put("receive", receive);
        return result;
    }
    
    /**
     * Get COMPARISON between raw transactions and optimized settlements for user
     * This helps show "Before Optimization" vs "After Optimization"
     * 
     * Returns map with:
     * - "raw_owe": Raw transactions where user owes (from actual transactions)
     * - "raw_receive": Raw transactions where user receives
     * - "optimized_owe": Optimized settlements where user owes (after greedy algorithm)
     * - "optimized_receive": Optimized settlements where user receives
     * - "savings": How many fewer transactions after optimization
     */
    public java.util.Map<String, Object> getOptimizedNotificationsWithComparison(String username) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        
        if (username == null) {
            result.put("raw_owe", java.util.Collections.emptyList());
            result.put("raw_receive", java.util.Collections.emptyList());
            result.put("optimized_owe", java.util.Collections.emptyList());
            result.put("optimized_receive", java.util.Collections.emptyList());
            result.put("savings", 0);
            return result;
        }
        
        // Get user's transactions (RAW - what they know)
        List<Transaction> userTransactions = listByUser(username);
        java.util.List<String> rawOwePeople = new java.util.ArrayList<>();
        java.util.List<String> rawReceivePeople = new java.util.ArrayList<>();
        
        for (Transaction tx : userTransactions) {
            String payer = tx.getPayerUsername();
            String payee = tx.getPayeeUsername();
            
            if (username.equalsIgnoreCase(payer)) {
                // User paid, so payee owes them (or they overpaid)
                if (!rawReceivePeople.contains(payee)) rawReceivePeople.add(payee);
            }
            if (username.equalsIgnoreCase(payee)) {
                // Someone paid for user, user might owe them
                if (!rawOwePeople.contains(payer)) rawOwePeople.add(payer);
            }
        }
        
        // Get optimized settlements (OPTIMIZED - what they should do)
        java.util.Map<String, java.util.List<com.smartsplitpro.model.PersonalSettlement>> optimized 
            = listPersonalNotifications(username);
        
        // Calculate savings
        int rawCount = rawOwePeople.size() + rawReceivePeople.size();
        int optimizedCount = optimized.get("owe").size() + optimized.get("receive").size();
        int savings = Math.max(0, rawCount - optimizedCount);
        
        result.put("raw_owe_people", rawOwePeople);
        result.put("raw_receive_people", rawReceivePeople);
        result.put("optimized_owe", optimized.get("owe"));
        result.put("optimized_receive", optimized.get("receive"));
        result.put("savings", savings);
        result.put("raw_count", rawCount);
        result.put("optimized_count", optimizedCount);
        
        return result;
    }

    // Return all personal settlements (settled and unsettled) where user is either payer or receiver
    public java.util.List<com.smartsplitpro.model.PersonalSettlement> listPersonalAll(String username) {
        if (username == null) return java.util.Collections.emptyList();
        try {
            java.util.List<com.smartsplitpro.model.PersonalSettlement> all = personalSettlementRepository.findByFromUserOrToUserOrderByCreatedAtDesc(username, username);
            all = dedupePersonalSettlements(all);
            attachContactDetails(all);
            return all;
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    @Transactional
    public boolean markPersonalSettled(Long id, String username) {
        if (id == null) return false;
        try {
            java.util.Optional<com.smartsplitpro.model.PersonalSettlement> opt = personalSettlementRepository.findById(id);
            if (opt.isEmpty()) return false;
            com.smartsplitpro.model.PersonalSettlement ps = opt.get();
            // allow either party involved to confirm settlement
            if (username == null || !(username.equals(ps.getFromUser()) || username.equals(ps.getToUser()))) return false;
            ps.setSettled(true);
            ps.setSettledAt(java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault()));
            ps.setSettledBy(username);
            personalSettlementRepository.save(ps);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void attachContactDetails(java.util.List<com.smartsplitpro.model.PersonalSettlement> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (com.smartsplitpro.model.PersonalSettlement ps : entries) {
            if (ps == null) continue;
            String from = ps.getFromUser();
            if (from != null && !from.isBlank()) {
                userRepository.findByUsernameIgnoreCase(from.trim()).ifPresent(u -> ps.setFromUserPhone(normalizePhoneNumber(u.getPhoneNumber())));
            }
            String to = ps.getToUser();
            if (to != null && !to.isBlank()) {
                userRepository.findByUsernameIgnoreCase(to.trim()).ifPresent(u -> ps.setToUserPhone(normalizePhoneNumber(u.getPhoneNumber())));
            }
        }
    }

    private String normalizePhoneNumber(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String cleaned = trimmed.replaceAll("[^0-9+]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private boolean isRegistered(String username) {
        if (username == null) return false;
        String trimmed = username.trim();
        if (trimmed.isEmpty()) return false;
        try {
            return userRepository.findByUsernameIgnoreCase(trimmed).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Public wrapper to check registration status. Kept for external callers.
     */
    public boolean isUserRegistered(String username) {
        return isRegistered(username);
    }

    /**
     * Create a personal settlement entry and mark it settled immediately.
     * Returns true on success.
     */
    @Transactional
    public boolean createAndMarkPersonalSettled(String fromUser, String toUser, java.math.BigDecimal amount, String settledBy) {
        if (fromUser == null || toUser == null || amount == null) return false;
        try {
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault());
            com.smartsplitpro.model.PersonalSettlement ps = new com.smartsplitpro.model.PersonalSettlement(fromUser, toUser, amount, now, false);
            ps.setRecipientRegistered(isRegistered(toUser));
            ps.setSettled(true);
            ps.setSettledAt(now);
            ps.setSettledBy(settledBy == null ? "unknown" : settledBy);
            personalSettlementRepository.save(ps);
            return true;
        } catch (Exception e) {
            System.out.println("Failed to createAndMarkPersonalSettled: " + e.getMessage());
            return false;
        }
    }

    private java.util.List<com.smartsplitpro.model.PersonalSettlement> dedupePersonalSettlements(java.util.List<com.smartsplitpro.model.PersonalSettlement> entries) {
        if (entries == null || entries.isEmpty()) return java.util.Collections.emptyList();
        java.util.LinkedHashMap<String, com.smartsplitpro.model.PersonalSettlement> map = new java.util.LinkedHashMap<>();
        for (com.smartsplitpro.model.PersonalSettlement ps : entries) {
            if (ps == null) continue;
            String from = ps.getFromUser() == null ? "" : ps.getFromUser().trim().toLowerCase();
            String to = ps.getToUser() == null ? "" : ps.getToUser().trim().toLowerCase();
            String amount = ps.getAmount() == null ? "0" : ps.getAmount().stripTrailingZeros().toPlainString();
            String txId = ps.getTransactionId() == null ? "-" : ps.getTransactionId().toString();
            String notify = ps.isNotifyOnly() ? "1" : "0";
            String registered = ps.isRecipientRegistered() ? "1" : "0";
            String created = ps.getCreatedAt() == null ? "-" : ps.getCreatedAt().toString();
            String key = from + '|' + to + '|' + amount + '|' + txId + '|' + notify + '|' + registered;
            if ("-".equals(txId)) key += '|' + created;
            map.putIfAbsent(key, ps);
        }
        return new java.util.ArrayList<>(map.values());
    }

    // ========================================================================
    // MANUAL SORTING ALGORITHMS - QUICK SORT IMPLEMENTATION
    // ========================================================================
    
    /**
     * Manual Quick Sort implementation for sorting balances in DESCENDING order
     * (Largest balance first - for creditors)
     * 
     * Algorithm: Quick Sort
     * Time Complexity: O(n log n) average, O(n²) worst case
     * Space Complexity: O(log n) for recursion stack
     */
    private void quickSortBalancesDescending(List<Balance> list, int low, int high) {
        if (low < high) {
            // Partition and get pivot index
            int pivotIndex = partitionDescending(list, low, high);
            
            // Recursively sort elements before and after partition
            quickSortBalancesDescending(list, low, pivotIndex - 1);
            quickSortBalancesDescending(list, pivotIndex + 1, high);
        }
    }
    
    private int partitionDescending(List<Balance> list, int low, int high) {
        // Choose last element as pivot
        BigDecimal pivot = list.get(high).getBalance();
        int i = low - 1; // Index of smaller element
        
        for (int j = low; j < high; j++) {
            // For descending order: if current element is GREATER than pivot
            if (list.get(j).getBalance().compareTo(pivot) > 0) {
                i++;
                // Swap elements
                Balance temp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, temp);
            }
        }
        
        // Swap pivot to correct position
        Balance temp = list.get(i + 1);
        list.set(i + 1, list.get(high));
        list.set(high, temp);
        
        return i + 1;
    }
    
    /**
     * Manual Quick Sort implementation for sorting balances in ASCENDING order
     * (Most negative first - for debtors)
     * 
     * Algorithm: Quick Sort
     * Time Complexity: O(n log n) average, O(n²) worst case
     * Space Complexity: O(log n) for recursion stack
     */
    private void quickSortBalancesAscending(List<Balance> list, int low, int high) {
        if (low < high) {
            // Partition and get pivot index
            int pivotIndex = partitionAscending(list, low, high);
            
            // Recursively sort elements before and after partition
            quickSortBalancesAscending(list, low, pivotIndex - 1);
            quickSortBalancesAscending(list, pivotIndex + 1, high);
        }
    }
    
    private int partitionAscending(List<Balance> list, int low, int high) {
        // Choose last element as pivot
        BigDecimal pivot = list.get(high).getBalance();
        int i = low - 1; // Index of smaller element
        
        for (int j = low; j < high; j++) {
            // For ascending order: if current element is LESS than pivot
            if (list.get(j).getBalance().compareTo(pivot) < 0) {
                i++;
                // Swap elements
                Balance temp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, temp);
            }
        }
        
        // Swap pivot to correct position
        Balance temp = list.get(i + 1);
        list.set(i + 1, list.get(high));
        list.set(high, temp);
        
        return i + 1;
    }
    
    /**
     * Manual Bubble Sort implementation for sorting transactions by timestamp
     * (Most recent first - descending order)
     * 
     * Algorithm: Bubble Sort
     * Time Complexity: O(n²) worst/average case, O(n) best case (already sorted)
     * Space Complexity: O(1) - in-place sorting
     * 
     * Educational Note: Bubble Sort is slower than Quick Sort but easier to understand.
     * Good for small datasets or when simplicity is important.
     */
    private void bubbleSortTransactionsByTimestamp(List<Transaction> list) {
        int n = list.size();
        boolean swapped;
        
        for (int i = 0; i < n - 1; i++) {
            swapped = false;
            
            for (int j = 0; j < n - 1 - i; j++) {
                // Compare adjacent elements
                java.time.LocalDateTime t1 = list.get(j).getTimestamp();
                java.time.LocalDateTime t2 = list.get(j + 1).getTimestamp();
                
                // Handle null timestamps (nulls go to end)
                if (t1 == null && t2 == null) continue;
                if (t1 == null) {
                    // Swap (null should be at end)
                    Transaction temp = list.get(j);
                    list.set(j, list.get(j + 1));
                    list.set(j + 1, temp);
                    swapped = true;
                    continue;
                }
                if (t2 == null) continue; // t1 is already before null
                
                // For descending order: swap if t1 < t2 (t2 is more recent)
                if (t1.compareTo(t2) < 0) {
                    Transaction temp = list.get(j);
                    list.set(j, list.get(j + 1));
                    list.set(j + 1, temp);
                    swapped = true;
                }
            }
            
            // If no swaps occurred, list is sorted
            if (!swapped) break;
        }
    }
    
    // ========================================================================
    // MANUAL SEARCHING ALGORITHMS IMPLEMENTATION
    // ========================================================================
    
    /**
     * Manual Linear Search implementation
     * Searches for a transaction by ID
     * 
     * Algorithm: Linear Search (Sequential Search)
     * Time Complexity: O(n) - checks each element one by one
     * Space Complexity: O(1)
     * 
     * Use Case: Unsorted data, small datasets
     */
    public Transaction linearSearchById(List<Transaction> transactions, Long targetId) {
        if (transactions == null || targetId == null) return null;
        
        // Check each element sequentially
        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);
            if (tx != null && tx.getId() != null && tx.getId().equals(targetId)) {
                System.out.println("[LINEAR SEARCH] Found at index: " + i);
                return tx; // Found!
            }
        }
        
        System.out.println("[LINEAR SEARCH] Not found");
        return null; // Not found
    }
    
    /**
     * Manual Binary Search implementation
     * Searches for a transaction by ID in a SORTED list
     * 
     * Algorithm: Binary Search (Divide and Conquer)
     * Time Complexity: O(log n) - much faster than linear search
     * Space Complexity: O(1) - iterative version
     * 
     * PREREQUISITE: List must be sorted by ID!
     * Use Case: Large sorted datasets
     */
    public Transaction binarySearchById(List<Transaction> sortedTransactions, Long targetId) {
        if (sortedTransactions == null || targetId == null) return null;
        
        int left = 0;
        int right = sortedTransactions.size() - 1;
        
        while (left <= right) {
            // Find middle point (avoid overflow)
            int mid = left + (right - left) / 2;
            Transaction midTx = sortedTransactions.get(mid);
            
            if (midTx == null || midTx.getId() == null) {
                // Skip null entries
                left = mid + 1;
                continue;
            }
            
            Long midId = midTx.getId();
            
            // Check if target is at mid
            if (midId.equals(targetId)) {
                System.out.println("[BINARY SEARCH] Found at index: " + mid);
                return midTx; // Found!
            }
            
            // If target is greater, ignore left half
            if (midId < targetId) {
                left = mid + 1;
            }
            // If target is smaller, ignore right half
            else {
                right = mid - 1;
            }
        }
        
        System.out.println("[BINARY SEARCH] Not found");
        return null; // Not found
    }
    
    /**
     * Manual search for user balance in balance list
     * 
     * Algorithm: Linear Search with early exit
     * Time Complexity: O(n)
     * 
     * This could be replaced with HashMap for O(1) lookup, 
     * but shown here for educational purposes
     */
    public BigDecimal searchUserBalance(List<Balance> balances, String username) {
        if (balances == null || username == null) return BigDecimal.ZERO;
        
        String normalizedUsername = username.trim().toLowerCase();
        
        // Linear search through balance list
        for (Balance balance : balances) {
            if (balance != null && balance.getUsername() != null) {
                String balanceUser = balance.getUsername().trim().toLowerCase();
                if (balanceUser.equals(normalizedUsername)) {
                    System.out.println("[BALANCE SEARCH] Found balance for " + username);
                    return balance.getBalance();
                }
            }
        }
        
        System.out.println("[BALANCE SEARCH] No balance found for " + username);
        return BigDecimal.ZERO;
    }
    
    /**
     * Manual search for transactions involving a specific user
     * 
     * Algorithm: Linear Search with multiple conditions
     * Time Complexity: O(n)
     */
    public List<Transaction> searchTransactionsByUser(List<Transaction> allTransactions, String username) {
        List<Transaction> userTransactions = new ArrayList<>();
        
        if (allTransactions == null || username == null) return userTransactions;
        
        String normalizedUsername = username.trim().toLowerCase();
        
        // Search through all transactions
        for (Transaction tx : allTransactions) {
            if (tx == null) continue;
            
            String payer = tx.getPayerUsername();
            String payee = tx.getPayeeUsername();
            String creator = tx.getCreatedBy();
            
            // Check if user is payer, payee, or creator
            boolean isPayer = payer != null && payer.trim().toLowerCase().equals(normalizedUsername);
            boolean isPayee = payee != null && payee.trim().toLowerCase().equals(normalizedUsername);
            boolean isCreator = creator != null && creator.trim().toLowerCase().equals(normalizedUsername);
            
            if (isPayer || isPayee || isCreator) {
                userTransactions.add(tx);
            }
        }
        
        System.out.println("[USER TRANSACTION SEARCH] Found " + userTransactions.size() + " transactions for " + username);
        return userTransactions;
    }

    // ==================== MANUAL HASH MAP IMPLEMENTATION ====================
    
    /**
     * Custom HashMap Implementation using Separate Chaining for collision resolution
     * 
     * Algorithm: Hash Table with Linked List for collisions
     * - Hash Function: Custom implementation based on string characters
     * - Collision Resolution: Separate Chaining (each bucket has a linked list)
     * - Time Complexity: O(1) average for put/get, O(n) worst case
     * - Space Complexity: O(n) where n is number of entries
     */
    private static class SimpleHashMap<K, V> {
        private static final int DEFAULT_CAPACITY = 16;
        private static final float LOAD_FACTOR = 0.75f;
        
        private Entry<K, V>[] buckets;
        private int size;
        
        @SuppressWarnings("unchecked")
        public SimpleHashMap() {
            buckets = new Entry[DEFAULT_CAPACITY];
            size = 0;
        }
        
        /**
         * Custom hash function - converts key to integer index
         * Algorithm: Polynomial rolling hash
         */
        private int hash(K key) {
            if (key == null) return 0;
            
            String keyStr = key.toString();
            int hash = 0;
            
            // Polynomial hash: hash = (hash * 31 + char) for each character
            for (int i = 0; i < keyStr.length(); i++) {
                hash = hash * 31 + keyStr.charAt(i);
            }
            
            // Make positive and fit within bucket size
            hash = Math.abs(hash);
            return hash % buckets.length;
        }
        
        /**
         * Put key-value pair into map
         * Time Complexity: O(1) average, O(n) worst case with many collisions
         */
        public void put(K key, V value) {
            if (key == null) return;
            
            // Check if we need to resize
            if ((float) size / buckets.length >= LOAD_FACTOR) {
                resize();
            }
            
            int index = hash(key);
            Entry<K, V> head = buckets[index];
            
            // Search for existing key in the chain
            Entry<K, V> current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    // Key exists, update value
                    current.value = value;
                    return;
                }
                current = current.next;
            }
            
            // Key doesn't exist, add new entry at the head of chain
            Entry<K, V> newEntry = new Entry<>(key, value);
            newEntry.next = head;
            buckets[index] = newEntry;
            size++;
        }
        
        /**
         * Get value for given key
         * Time Complexity: O(1) average, O(n) worst case
         */
        public V get(K key) {
            if (key == null) return null;
            
            int index = hash(key);
            Entry<K, V> current = buckets[index];
            
            // Linear search through chain
            while (current != null) {
                if (current.key.equals(key)) {
                    return current.value;
                }
                current = current.next;
            }
            
            return null; // Key not found
        }
        
        /**
         * Get all keys in the map
         */
        public List<K> keys() {
            List<K> keyList = new ArrayList<>();
            
            for (Entry<K, V> bucket : buckets) {
                Entry<K, V> current = bucket;
                while (current != null) {
                    keyList.add(current.key);
                    current = current.next;
                }
            }
            
            return keyList;
        }
        
        /**
         * Get number of entries
         */
        public int size() {
            return size;
        }
        
        /**
         * Resize the hash table when load factor exceeds threshold
         * Algorithm: Create new larger array, rehash all entries
         * Time Complexity: O(n)
         */
        @SuppressWarnings("unchecked")
        private void resize() {
            Entry<K, V>[] oldBuckets = buckets;
            buckets = new Entry[oldBuckets.length * 2];
            size = 0;
            
            // Rehash all entries
            for (Entry<K, V> bucket : oldBuckets) {
                Entry<K, V> current = bucket;
                while (current != null) {
                    put(current.key, current.value);
                    current = current.next;
                }
            }
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            
            for (Entry<K, V> bucket : buckets) {
                Entry<K, V> current = bucket;
                while (current != null) {
                    if (!first) sb.append(", ");
                    sb.append(current.key).append("=").append(current.value);
                    first = false;
                    current = current.next;
                }
            }
            
            sb.append("}");
            return sb.toString();
        }
        
        /**
         * Entry node for linked list in each bucket
         */
        private static class Entry<K, V> {
            K key;
            V value;
            Entry<K, V> next;
            
            Entry(K key, V value) {
                this.key = key;
                this.value = value;
                this.next = null;
            }
        }
    }

}
