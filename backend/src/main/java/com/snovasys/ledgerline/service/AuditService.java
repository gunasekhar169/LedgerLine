package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.model.AuditLog;
import com.snovasys.ledgerline.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(String username, String action, String targetResource, String status, String details, String ipAddress) {
        try {
            AuditLog auditLog = new AuditLog(
                    username != null ? username : "ANONYMOUS",
                    action,
                    targetResource,
                    status,
                    details,
                    ipAddress != null ? ipAddress : "0.0.0.0"
            );
            auditLogRepository.save(auditLog);
            log.info("AUDIT: [{}] {} on {} - {}: {}", status, action, targetResource, username, details);
        } catch (Exception e) {
            log.error("Failed to persist audit log: {}", e.getMessage(), e);
        }
    }

    public List<AuditLog> getAllLogs() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AuditLog> getLogsByUsername(String username) {
        return auditLogRepository.findByActorUsernameOrderByCreatedAtDesc(username);
    }
}
