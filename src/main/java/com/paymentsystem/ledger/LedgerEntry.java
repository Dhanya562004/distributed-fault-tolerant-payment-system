package com.paymentsystem.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, append-only double-entry ledger record.
 *
 * Every payment, refund, and balance initialization writes matching DEBIT and CREDIT
 * entries in the same database transaction as the balance change.
 *
 * Guaranteed append-only:
 * - Marked {@code @Immutable} so Hibernate ignores dirty checking.
 * - {@code @PreUpdate} and {@code @PreRemove} throw {@link UnsupportedOperationException}.
 * - Database unique constraint enforces at most one DEBIT and one CREDIT per financial transaction.
 */
@Entity
@Table(
        name = "ledger_entries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ledger_tx_side", columnNames = {"transaction_id", "entry_side"})
        },
        indexes = {
                @Index(name = "idx_ledger_account_id", columnList = "account_id"),
                @Index(name = "idx_ledger_tx_id", columnList = "transaction_id"),
                @Index(name = "idx_ledger_currency_created", columnList = "currency, created_at")
        }
)
@Immutable
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false, unique = true, length = 64)
    private String entryId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private LedgerEntryType entryType;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_side", nullable = false, length = 16)
    private EntrySide entrySide;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Default constructor required by JPA.
     */
    protected LedgerEntry() {
    }

    public LedgerEntry(String entryId,
                       String transactionId,
                       LedgerEntryType entryType,
                       String accountId,
                       EntrySide entrySide,
                       Long amount,
                       String currency,
                       String description,
                       Instant createdAt) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive (> 0), got: " + amount);
        }
        this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.entryType = Objects.requireNonNull(entryType, "entryType must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.entrySide = Objects.requireNonNull(entrySide, "entrySide must not be null");
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "USD";
        this.description = description;
        this.createdAt = (createdAt != null) ? createdAt : Instant.now();
    }

    @PreUpdate
    protected void onPreUpdate() {
        throw new UnsupportedOperationException("Ledger is append-only: updates are strictly prohibited");
    }

    @PreRemove
    protected void onPreRemove() {
        throw new UnsupportedOperationException("Ledger is append-only: deletions are strictly prohibited");
    }

    public Long getId() {
        return id;
    }

    public String getEntryId() {
        return entryId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public String getAccountId() {
        return accountId;
    }

    public EntrySide getEntrySide() {
        return entrySide;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerEntry that = (LedgerEntry) o;
        return Objects.equals(entryId, that.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId);
    }

    @Override
    public String toString() {
        return "LedgerEntry{" +
                "entryId='" + entryId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", entryType=" + entryType +
                ", accountId='" + accountId + '\'' +
                ", entrySide=" + entrySide +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
