package com.smartsplitpro.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "transaction_history")
public class TransactionHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long transactionId;

    private String action; // CREATED, DELETED, UPDATED

    @Lob
    private String payload; // JSON snapshot of the transaction

    private String performedBy;

    private OffsetDateTime timestamp;

    public TransactionHistory() {}

    public TransactionHistory(Long transactionId, String action, String payload, String performedBy, OffsetDateTime timestamp) {
        this.transactionId = transactionId;
        this.action = action;
        this.payload = payload;
        this.performedBy = performedBy;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public String getAction() { return action; }
    public String getPayload() { return payload; }
    public String getPerformedBy() { return performedBy; }
    public OffsetDateTime getTimestamp() { return timestamp; }

    public void setId(Long id) { this.id = id; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public void setAction(String action) { this.action = action; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }
}
