package com.smartsplitpro.repository;

import com.smartsplitpro.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    java.util.List<Transaction> findByPayerUsernameOrPayeeUsername(String payerUsername, String payeeUsername);
    java.util.List<Transaction> findByPayerUsernameIgnoreCaseOrPayeeUsernameIgnoreCase(String payerUsername, String payeeUsername);
    java.util.List<Transaction> findByCreatedBy(String createdBy);
    java.util.List<Transaction> findByCreatedByIgnoreCase(String createdBy);
}
