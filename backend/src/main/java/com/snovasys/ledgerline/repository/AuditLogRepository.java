package com.snovasys.ledgerline.repository;

import com.snovasys.ledgerline.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderByCreatedAtDesc();
    List<AuditLog> findByActorUsernameOrderByCreatedAtDesc(String actorUsername);
}
