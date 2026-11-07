# LedgerLine — AI Usage & Engineering Decisions (PROMPTS.md)

This log documents the prompts provided to AI tools during the development of **LedgerLine**, the code and architectural patterns produced, the vulnerabilities or flaws identified upon manual review, and the engineering corrections implemented.

---

### Entry 1 — Concurrency & Account Row Locking
- **Date:** 2026-11-06
- **What I asked for:**
  > "How should I handle concurrent withdrawal and transfer requests on an account in Spring Boot JPA to prevent double spending?"
- **What the tool produced:**
  The AI suggested using JPA `@Version` optimistic locking on the `Account` entity and wrapping transfer logic in an application-level retry loop.
- **What was wrong with it:**
  Under high-frequency concurrent traffic (e.g., automated scripts or sudden simultaneous payments), optimistic locking causes excessive `OptimisticLockingFailureException` rollbacks. Retrying repeatedly under contention spikes database CPU and introduces latency without guaranteeing non-negative balances under race conditions.
- **What I did instead:**
  Replaced optimistic locking with explicit pessimistic write locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) using `SELECT ... FOR UPDATE` via `accountRepository.findByIdForUpdate()`. Added a database-level check constraint `CHECK (balance >= 0.00)` to guarantee at the storage engine level that an account can never be overdrawn.
- **Commit Reference:** `feat: implement pessimistic row locking and database balance constraint`

---

### Entry 2 — Bi-Directional Transfer Deadlock Prevention
- **Date:** 2026-11-07
- **What I asked for:**
  > "Write a transfer method in Spring Boot that locks both the source account and destination account before updating balances."
- **What the tool produced:**
  The AI wrote logic that locked the `fromAccountId` first, followed by locking `toAccountId`.
- **What was wrong with it:**
  This introduced a severe **Database Deadlock** vulnerability! If Alice transfers to Bob while Bob simultaneously transfers to Alice, Thread 1 locks Alice and waits for Bob, while Thread 2 locks Bob and waits for Alice. The database terminates one transaction with a `DeadlockLoserDataAccessException`.
- **What I did instead:**
  Implemented deterministic lock ordering by sorting the two account IDs prior to acquiring locks:
  ```java
  Long firstId = Math.min(fromAccountId, toAccountId);
  Long secondId = Math.max(fromAccountId, toAccountId);
  Account first = accountRepository.findByIdForUpdate(firstId).orElseThrow(...);
  Account second = accountRepository.findByIdForUpdate(secondId).orElseThrow(...);
  ```
  Both threads now acquire locks in ascending ID order, converting cyclic contention into safe sequential queueing.
- **Commit Reference:** `fix: resolve bi-directional transfer deadlock via deterministic lock ordering`

---

### Entry 3 — Access Control & Insecure Direct Object Reference (IDOR) *(AUTH ENTRY 1)*
- **Date:** 2026-11-07
- **What I asked for:**
  > "Create a REST controller endpoint for transferring funds where the client provides fromAccountId, toAccountId, and amount."
- **What the tool produced:**
  The AI generated a `@PostMapping("/transfer")` controller that took the `fromAccountId` from the JSON body and directly executed the transfer without verifying ownership.
- **What was wrong with it:**
  **Critical IDOR vulnerability (CWE-639)!** Any authenticated user could debit money from any other user's account merely by guessing or supplying the victim's `fromAccountId`.
- **What I did instead:**
  Implemented mandatory ownership and authorization verification:
  ```java
  User currentUser = authService.getCurrentUser();
  boolean isOwner = fromAccount.getUser() != null && fromAccount.getUser().getId().equals(currentUser.getId());
  boolean isAdmin = currentUser.getRole() == Role.ROLE_ADMIN;

  if (!isOwner && !isAdmin) {
      auditService.logEvent(currentUser.getUsername(), "IDOR_TRANSFER_BLOCKED", "accounts/" + fromAccount.getId(), "BLOCKED",
              "User tried to debit unowned account", clientIp);
      throw new UnauthorizedAccountAccessException("Access denied: You do not have permission to debit account " + fromAccount.getId());
  }
  ```
  Added automated security integration tests (`AccessControlAndIdorTest.java`) ensuring unauthorized requests are rejected with HTTP 403 Forbidden.
- **Commit Reference:** `security: enforce account ownership checks and prevent IDOR on money transfers`

---

### Entry 4 — Append-Only Audit Trail & Immutability *(AUDIT TRAIL ENTRY 2)*
- **Date:** 2026-11-08
- **What I asked for:**
  > "Create an audit log entity and service in Spring Boot for recording financial and security events."
- **What the tool produced:**
  The AI provided a standard JPA entity with mutable fields, setters, and standard CRUD repository methods (`save`, `delete`, `deleteAll`).
- **What was wrong with it:**
  In financial accounting and compliance, audit trails must be **strictly immutable and append-only**. Providing update or deletion capabilities creates a critical vulnerability where an attacker or rogue administrator could alter or scrub audit logs.
- **What I did instead:**
  1. Configured `@Column(updatable = false)` on all audit and journal entry fields.
  2. Disallowed all deletion/modification methods in the service layer.
  3. Built an Auditor Cryptographic Verification Engine (`LedgerVerificationService.java`) that generates a SHA-256 hash chain over all journal entries, ensuring that any unauthorized retroactive modification invalidates the system proof digest.
- **Commit Reference:** `security: enforce append-only immutability and cryptographic ledger verification`

---

### Entry 5 — Idempotency & Replay Attack Protection
- **Date:** 2026-11-08
- **What I asked for:**
  > "How to implement Idempotency-Key support in Spring Boot REST controllers to avoid double charges?"
- **What the tool produced:**
  The AI suggested storing `idempotencyKey` strings in an in-memory `ConcurrentHashMap` with a boolean flag.
- **What was wrong with it:**
  1. In-memory caching loses idempotency state if the server restarts or scales horizontally across multiple instances.
  2. It failed to check if the request payload was altered, allowing an attacker to reuse an existing idempotency key with a completely different transaction payload (Idempotency Key Substitution attack).
- **What I did instead:**
  1. Designed persistent table `idempotency_records` in PostgreSQL.
  2. Calculated a SHA-256 hash of the incoming request JSON payload.
  3. If the key exists, compared hashes: if identical, returned the cached response with `Idempotent-Replayed: true`; if mismatched, returned HTTP 422 `IdempotencyConflictException`.
- **Commit Reference:** `feat: implement persistent database idempotency engine with payload hashing`

---

### Entry 6 — Financial Arithmetic Precision & Rounding
- **Date:** 2026-11-09
- **What I asked for:**
  > "Define account balance and transaction amount fields in Java and database schema."
- **What the tool produced:**
  The AI used Java `double` and `float` primitives for balances and amounts, and `DOUBLE PRECISION` in SQL.
- **What was wrong with it:**
  Binary floating-point types (`double`/`float`) cannot accurately represent base-10 decimal fractions (e.g. `0.1 + 0.2 = 0.30000000000000004`). In financial ledgers, this leads to rounding discrepancies where total debits fail to match total credits.
- **What I did instead:**
  Converted all financial values to `java.math.BigDecimal` with explicit scale 4 (`BigDecimal.setScale(4)`) and mapped them to SQL `NUMERIC(19, 4)`.
- **Commit Reference:** `refactor: convert all currency and balances to BigDecimal and NUMERIC(19,4)`
