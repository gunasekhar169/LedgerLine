package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.AuditLog;

import java.time.LocalDateTime;

public record AuditLogResponse(
    Long id,
    String actorUsername,
    String action,
    String targetResource,
    String status,
    String details,
    String ipAddress,
    LocalDateTime createdAt
) {
    public static AuditLogResponse fromEntity(AuditLog log) {
        return new AuditLogResponse(
            log.getId(),
            log.getActorUsername(),
            log.getAction(),
            log.getTargetResource(),
            log.getStatus(),
            log.getDetails(),
            log.getIpAddress(),
            log.getCreatedAt()
        );
    }
}
