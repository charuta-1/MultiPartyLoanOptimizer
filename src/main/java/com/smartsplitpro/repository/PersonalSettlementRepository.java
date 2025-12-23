package com.smartsplitpro.repository;

import com.smartsplitpro.model.PersonalSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PersonalSettlementRepository extends JpaRepository<PersonalSettlement, Long> {
    List<PersonalSettlement> findByFromUserAndSettledFalseOrderByCreatedAtDesc(String fromUser);
    List<PersonalSettlement> findByToUserAndSettledFalseOrderByCreatedAtDesc(String toUser);
    // All settlements involving a user (either as payer or receiver), newest first
    List<PersonalSettlement> findByFromUserOrToUserOrderByCreatedAtDesc(String fromUser, String toUser);
    java.util.Optional<PersonalSettlement> findFirstByFromUserOrToUserOrderByCreatedAtDesc(String fromUser, String toUser);
    java.util.List<PersonalSettlement> findByTransactionId(Long transactionId);
    void deleteAllByTransactionId(Long transactionId);
}
