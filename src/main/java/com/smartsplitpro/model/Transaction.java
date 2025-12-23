package com.smartsplitpro.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    private BigDecimal amount;

    private LocalDateTime timestamp;

    // For simplicity, store payer and payee as usernames. In real app, use relations.
    private String payerUsername;
    private String payeeUsername;
    @Column(name = "created_by")
    private String createdBy;

    public Transaction() {}

    public Transaction(String description, BigDecimal amount, LocalDateTime timestamp, String payerUsername, String payeeUsername) {
        this.description = description;
        this.amount = amount;
        this.timestamp = timestamp;
        this.payerUsername = payerUsername;
        this.payeeUsername = payeeUsername;
    }

    public Transaction(String description, BigDecimal amount, LocalDateTime timestamp, String payerUsername, String payeeUsername, String createdBy) {
        this(description, amount, timestamp, payerUsername, payeeUsername);
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getPayerUsername() { return payerUsername; }
    public void setPayerUsername(String payerUsername) { this.payerUsername = payerUsername; }
    public String getPayeeUsername() { return payeeUsername; }
    public void setPayeeUsername(String payeeUsername) { this.payeeUsername = payeeUsername; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
