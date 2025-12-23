package com.smartsplitpro.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
public class PersonalSettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // user who owes money
    private String fromUser;
    // user who should receive
    private String toUser;

    private BigDecimal amount;

    private boolean settled = false;

    // Link back to a specific transaction when this entry originates from a transaction
    private Long transactionId;

    // Flag to indicate that this entry was derived from a concrete transaction (as opposed to a snapshot)
    private Boolean fromTransaction = Boolean.FALSE;

    // Whether the recipient (toUser) had a registered account when this entry was created
    @Column(name = "recipient_registered")
    private Boolean recipientRegistered = Boolean.TRUE;

    private OffsetDateTime settledAt;

    private String settledBy;

    // when true, this entry should only trigger a notification for the user
    // and the usual dashboard graphs / network view should be suppressed
    // Use Boolean to allow null/default handling during schema evolution
    private Boolean notifyOnly = Boolean.FALSE;
    private OffsetDateTime createdAt;

    @Transient
    private String toUserPhone;

    @Transient
    private String fromUserPhone;

    public PersonalSettlement() {}

    public PersonalSettlement(String fromUser, String toUser, BigDecimal amount, OffsetDateTime createdAt) {
        this(fromUser, toUser, amount, createdAt, false, null, true);
    }

    public PersonalSettlement(String fromUser, String toUser, BigDecimal amount, OffsetDateTime createdAt, boolean notifyOnly) {
        this(fromUser, toUser, amount, createdAt, notifyOnly, null, false);
    }

    public PersonalSettlement(String fromUser, String toUser, BigDecimal amount, OffsetDateTime createdAt, boolean notifyOnly, Long transactionId) {
        this(fromUser, toUser, amount, createdAt, notifyOnly, transactionId, true);
    }

    public PersonalSettlement(String fromUser, String toUser, BigDecimal amount, OffsetDateTime createdAt, boolean notifyOnly, Long transactionId, boolean markFromTransaction) {
        this.fromUser = fromUser;
        this.toUser = toUser;
        this.amount = amount;
        this.createdAt = createdAt;
        this.settled = false;
        this.notifyOnly = notifyOnly;
        this.transactionId = transactionId;
        this.fromTransaction = markFromTransaction ? Boolean.TRUE : Boolean.FALSE;
        this.recipientRegistered = Boolean.TRUE;
        this.settledAt = null;
        this.settledBy = null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFromUser() { return fromUser; }
    public void setFromUser(String fromUser) { this.fromUser = fromUser; }
    public String getToUser() { return toUser; }
    public void setToUser(String toUser) { this.toUser = toUser; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean settled) { this.settled = settled; }
    public boolean isNotifyOnly() { return notifyOnly != null && notifyOnly.booleanValue(); }
    public void setNotifyOnly(boolean notifyOnly) { this.notifyOnly = notifyOnly; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public boolean isFromTransaction() { return fromTransaction != null && fromTransaction.booleanValue(); }
    public void setFromTransaction(boolean fromTransaction) { this.fromTransaction = fromTransaction; }
    public boolean isRecipientRegistered() { return recipientRegistered != null && recipientRegistered.booleanValue(); }
    public void setRecipientRegistered(Boolean recipientRegistered) { this.recipientRegistered = recipientRegistered; }
    public OffsetDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(OffsetDateTime settledAt) { this.settledAt = settledAt; }
    public String getSettledBy() { return settledBy; }
    public void setSettledBy(String settledBy) { this.settledBy = settledBy; }
    public String getToUserPhone() { return toUserPhone; }
    public void setToUserPhone(String toUserPhone) { this.toUserPhone = toUserPhone; }
    public String getFromUserPhone() { return fromUserPhone; }
    public void setFromUserPhone(String fromUserPhone) { this.fromUserPhone = fromUserPhone; }
}
