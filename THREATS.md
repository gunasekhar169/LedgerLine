# LedgerLine — Threat Model & Security Assurance (THREATS.md)

This threat model outlines the attack vectors analyzed during the design of **LedgerLine**, the architectural defenses engineered into the system, and the corresponding automated integration tests that prove each mitigation works.

---

## Threat Matrix & Test Traceability

| Threat ID | Threat Vector & Description | Severity | Architectural Mitigation | Automated Test File & Method |
| :--- | :--- | :--- | :--- | :--- |
| **THREAT-01** | **Race Condition / Concurrent Double-Spend**<br>Attacker sends 10 simultaneous withdrawal requests to overdraw an account with $100. | **CRITICAL** | Pessimistic write locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) + DB constraint `CHECK(balance >= 0.00)` | `ConcurrencyAndRaceConditionTest.java`<br>`testConcurrentWithdrawalsPreventDoubleSpend()` |
| **THREAT-02** | **Replay Attack / Duplicate Money Transfer**<br>Network drop or intentional duplicate submission sends the same payment request twice. | **HIGH** | Idempotency engine (`Idempotency-Key` header) caching signed transaction responses and returning cached execution | `IdempotencyAndReplayAttackTest.java`<br>`testReplayAttackPrevention()` |
| **THREAT-03** | **Idempotency Key Payload Tampering**<br>Attacker attempts to reuse an existing `Idempotency-Key` with a different destination or higher amount. | **HIGH** | SHA-256 payload hashing validating payload match; throws `IdempotencyConflictException` (HTTP 422) on mismatch | `IdempotencyAndReplayAttackTest.java`<br>`testIdempotencyPayloadTampering()` |
| **THREAT-04** | **Bi-Directional Transfer Deadlocks**<br>Simultaneous transfers between Account A and Account B (`A -> B` and `B -> A`) cause database deadlocks. | **HIGH** | Deterministic lock ordering: always acquire locks in ascending account ID order (`Math.min` then `Math.max`) | `BiDirectionalTransferDeadlockTest.java`<br>`testBiDirectionalTransfersPreventDeadlock()` |
| **THREAT-05** | **Insecure Direct Object Reference (IDOR) on Transfers/Withdrawals**<br>Authenticated User B debits User A's account by specifying User A's account ID. | **CRITICAL** | Ownership verification in service layer ensuring debit accounts match the caller's identity; logs security audit event | `AccessControlAndIdorTest.java`<br>`testPreventIdorTransfer()` & `testPreventIdorWithdrawal()` |
| **THREAT-06** | **Unauthorized Statement Inspection (Information Disclosure)**<br>User B attempts to view User A's transaction statement and running balances. | **MEDIUM** | Strict object-level ACL checking in `getAccountStatement()`; rejects unauthorized readers with HTTP 403 Forbidden | `AccessControlAndIdorTest.java`<br>`testPreventIdorStatementRead()` |
| **THREAT-07** | **Zero / Negative Amount Exploitation**<br>Submitting negative deposits (theft) or negative transfers to illegitimately inflate balances. | **HIGH** | Dual-layer validation: Jakarta `@DecimalMin("0.01")` and service-level strict positivity assertion (`amount > 0`) | `NegativeAndZeroAmountPreventionTest.java`<br>`testRejectZeroAndNegativeAmounts()` |
| **THREAT-08** | **Ledger Imbalance / Money Creation Out of Thin Air**<br>Single-entry bugs or rounding errors causing total system debits to diverge from credits. | **CRITICAL** | Atomic double-entry bookkeeping with balanced `DEBIT` and `CREDIT` entries + SHA-256 cryptographic chain proof | `LedgerMathematicalProofTest.java`<br>`testLedgerBooksAlwaysBalance()` |
| **THREAT-09** | **Audit Trail Retroactive Tampering**<br>Rogue user modifies past audit or ledger entries to conceal fraudulent transactions. | **CRITICAL** | Append-only database tables with no `UPDATE` or `DELETE` endpoints + cryptographic SHA-256 integrity hash chain | `LedgerVerificationService.java`<br>`verifySystemLedger()` |
| **THREAT-10** | **Vertical Privilege Escalation**<br>Standard account holders attempting to access auditor integrity proof or admin user management. | **HIGH** | Method-level security (`@PreAuthorize("hasAnyAuthority(...)")`) and role-based URL matchers in `SecurityConfig` | `SecurityConfig.java` & `AuditController.java` |

---

## Detailed Threat Scenario Breakdowns

### 1. Concurrency & Race Condition Defense
- **Attack Scenario:** An account has a balance of $100.00. An attacker writes a script that spawns 10 threads hitting `POST /api/transactions/withdraw` simultaneously with amount $100.00. In naive systems, all threads read balance as $100 before any thread writes, resulting in an account balance of -$900.00.
- **LedgerLine Defense:** 
  1. The transaction acquires an explicit pessimistic write lock (`SELECT ... FOR UPDATE`) on the account record.
  2. Database reads are serialized. Thread 1 locks the row, executes the withdrawal, and updates balance to $0.00.
  3. When Threads 2–10 acquire the lock sequentially, they read the fresh balance ($0.00) and immediately fail with `InsufficientFundsException`.
  4. As an additional fail-safe, the database table has a hard constraint: `CHECK (balance >= 0.00)`.

### 2. Idempotency & Replay Attack Defense
- **Attack Scenario:** A customer transfers $500.00 for rent. Due to a momentary cellular timeout, the client automatically retries the POST request. The server receives two requests with the same financial intent.
- **LedgerLine Defense:** 
  1. Every mutating request carries an `Idempotency-Key` header.
  2. The server computes a SHA-256 hash of the JSON payload.
  3. If the key exists:
     - If the payload matches: the server immediately returns the previously committed transaction response with header `Idempotent-Replayed: true`.
     - If the payload differs: the server flags a replay tampering attempt and throws `IdempotencyConflictException` (HTTP 422).

### 3. Deadlock Prevention in Bi-Directional Transfers
- **Attack Scenario:** Alice transfers to Bob while Bob simultaneously transfers to Alice. Thread 1 locks Alice and requests Bob. Thread 2 locks Bob and requests Alice. This causes a circular database lock (Deadlock).
- **LedgerLine Defense:** 
  - LedgerLine enforces **Strict Lock Hierarchy**:
    ```java
    Long firstId = Math.min(fromAccountId, toAccountId);
    Long secondId = Math.max(fromAccountId, toAccountId);
    accountRepository.findByIdForUpdate(firstId);
    accountRepository.findByIdForUpdate(secondId);
    ```
  - Both threads lock the account with the smaller ID first, converting cyclic contention into deterministic sequential queueing.
