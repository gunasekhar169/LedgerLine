package com.snovasys.ledgerline.repository;

import com.snovasys.ledgerline.model.EntryType;
import com.snovasys.ledgerline.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    List<JournalEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<JournalEntry> findByTransactionIdOrderByCreatedAtAsc(Long transactionId);

    List<JournalEntry> findAllByOrderByCreatedAtAsc();

    @Query("SELECT SUM(j.amount) FROM JournalEntry j WHERE j.entryType = :entryType")
    BigDecimal sumAmountByEntryType(@Param("entryType") EntryType entryType);

    @Query("SELECT SUM(CASE WHEN j.entryType = com.snovasys.ledgerline.model.EntryType.CREDIT THEN j.amount ELSE -j.amount END) FROM JournalEntry j WHERE j.account.id = :accountId")
    BigDecimal calculateNetBalanceForAccount(@Param("accountId") Long accountId);
}
