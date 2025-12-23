package com.smartsplitpro.controller;

import com.smartsplitpro.model.Transaction;
import com.smartsplitpro.service.TransactionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("transaction", new Transaction());
        return "transaction_form";
    }

    @PostMapping("/add")
    public String add(@ModelAttribute Transaction transaction) {
        if (transaction.getTimestamp() == null) transaction.setTimestamp(LocalDateTime.now());
        if (transaction.getAmount() == null) transaction.setAmount(BigDecimal.ZERO);
        transactionService.addTransaction(transaction);
        return "redirect:/";
    }

    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("transactions", transactionService.listAll());
        return "dashboard"; // reuse dashboard to show transactions in this scaffold
    }
}
