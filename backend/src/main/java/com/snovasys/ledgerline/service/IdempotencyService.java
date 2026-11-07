package com.snovasys.ledgerline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.exception.IdempotencyConflictException;
import com.snovasys.ledgerline.model.IdempotencyRecord;
import com.snovasys.ledgerline.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository, ObjectMapper objectMapper) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    public String computePayloadHash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException | Exception e) {
            throw new RuntimeException("Error computing request payload hash", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> checkIdempotency(String idempotencyKey, Long userId, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            // Validate that the payload hash matches
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' has already been used with a different request payload."
                );
            }
            return Optional.of(record);
        }
        return Optional.empty();
    }

    @Transactional
    public void recordSuccess(String idempotencyKey, Long userId, String requestHash, int statusCode, Object responsePayload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        try {
            String responseJson = objectMapper.writeValueAsString(responsePayload);
            IdempotencyRecord record = new IdempotencyRecord(
                    idempotencyKey.trim(),
                    userId != null ? userId : 0L,
                    requestHash,
                    statusCode,
                    responseJson,
                    LocalDateTime.now().plusHours(24)
            );
            idempotencyRecordRepository.save(record);
        } catch (Exception e) {
            // Log but don't fail transaction if already committed
        }
    }
}
