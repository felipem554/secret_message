# Memory Hardening Plan

## Purpose

This is the implementation plan for reducing key-material exposure in JVM
memory. It is intentionally operational: what to change, how to verify it, and
what counts as complete.

For the learning background behind this plan, read
[`JVM_MEMORY_SECURITY_PRIMER.md`](JVM_MEMORY_SECURITY_PRIMER.md) first. That
primer explains heap vs stack, garbage collection, `String` risks, memory leaks,
and the verification labs in more detail.

## Goal

The service generates an AES-256 key for each secret message and returns that
key to the client. For a short time, the key exists inside the JVM. The goal is
to reduce the chance that key material leaks through:

- JVM heap dumps.
- OS core dumps.
- Swap.
- Debugger or Java-agent attachment.
- `ptrace` or `/proc/<pid>/mem` access.
- Application logs.
- Unnecessary long-lived Java object references.

The accepted security posture is: key material may exist briefly in JVM memory,
but there should be no easy path for an unprivileged attacker, accidental heap
dump, core dump, swap file, debugger, or log line to recover it.

## Sensitive Material

This plan protects two classes of key material.

1. **Per-message AES keys**
   - Generated for each new message.
   - Returned to the client.
   - Used later by the client to reveal the message.
   - Should exist in server memory for the shortest practical time.

2. **Master Idempotency Encryption Key (MIEK)**
   - Loaded from `app.idempotency.master-key`.
   - Used to encrypt per-message AES keys stored in idempotency records.
   - Lives for the process lifetime.
   - Must never be logged, dumped, or copied into uncontrolled objects.

Message plaintext is also sensitive, but this plan focuses on key material
because key leakage can expose encrypted messages beyond one request.

## Threat Model

We assume an attacker can do one of the following:

1. Trigger or read a JVM heap dump, such as through `jmap`, OOM heap dump flags,
   JMX, or `HotSpotDiagnosticMXBean`.
2. Trigger or read an OS core dump.
3. Read process memory directly through `ptrace` or `/proc/<pid>/mem`.
4. Read swap-backed pages from disk after the process has terminated.
5. Read application logs.

We do not defend against:

- An attacker with root on the host who can freely read live memory.
- CPU side-channel attacks such as Spectre-class attacks.
- A malicious JVM, malicious OS, or compromised container runtime.
- Hardware-level compromise.

The realistic goal is to raise the cost from "trivial accidental leak" to
"requires serious system access."

## Current Key Lifecycle

The current create flow is:

1. `SecretMessageService.createSecretMessage(...)` generates a `SecretKey`.
2. `CryptoUtil.encryptMessage(...)` calls `secretKey.getEncoded()`.
3. `SecretMessageIdentifier` stores the key as Base64 text in `aeskey`.
4. `MessageController` copies that value into `CreateMessageResponse`.
5. Jackson serializes the response to JSON.
6. If idempotency is enabled, `IdempotencyService.store(...)` decodes the
   Base64 string back into bytes and encrypts it with the MIEK.

Current gaps:

- `SecretMessageIdentifier.aeskey` is a `String`.
- `CreateMessageResponse.aesKey` is a `String`.
- `CryptoUtil.decryptMessage(String encryptedContent, String aesKey)` accepts a
  key as `String`.
- `IdempotencyService.store(...)` accepts `aesKeyBase64` as `String`.
- `docker-entrypoint.sh` does not currently pass production hardening JVM flags.
- Debug mode can enable JDWP when `DEBUG=true`; this must be explicitly blocked
  in production.

## Application Hardening Tasks

### 1. Keep Key Material Out Of Internal `String` Values

Target state:

- `SecretMessageIdentifier` stores key bytes as `byte[]`, not `SecretKey` and
  not Base64 `String`.
- `CryptoUtil` accepts `byte[] keyBytes` for encryption/decryption operations.
- `IdempotencyService.store(...)` accepts `byte[] aesKeyBytes`.
- The HTTP response boundary is the only layer that exposes a Base64 key to
  JSON.
- The Base64 value exists only long enough to write the HTTP response.

Acceptance criteria:

- No internal domain or service object stores the per-message AES key as
  `String`.
- No internal service method accepts the per-message AES key as `String`.
- A search for `aesKey`, `aeskey`, `secretKey`, and `getEncoded()` is reviewed;
  each remaining match has a documented reason to exist.

### 2. Zero Temporary Key Buffers

Use `try/finally` around every application-owned buffer that contains key bytes:

```java
byte[] keyBytes = generateKeyBytes();
try {
    // use keyBytes
} finally {
    Arrays.fill(keyBytes, (byte) 0);
}
```

Zeroing is best-effort. It removes the application-owned copy, but the JVM,
crypto provider, Base64 encoder, or serializer may have made other copies.

Acceptance criteria:

- Every method that creates, decodes, derives, or copies a key has a visible
  owner responsible for wiping it.
- Cleanup happens on both success and failure paths.
- Tests cover failure paths around encryption, idempotency storage, and response
  serialization.

### 3. Reduce Copies During Response Serialization

Standard Jackson serialization can create intermediate buffers while turning a
`byte[]` into Base64 JSON text. Those copies are not easy to wipe.

Preferred implementation options:

1. Use a custom Jackson serializer for the create response that writes the
   encoded key directly to the JSON generator and wipes application-owned
   buffers immediately after writing.
2. Use `StreamingResponseBody` for the create endpoint and write the response
   manually, wiping both source and encoded buffers after the stream is flushed.

Do not try to wipe the key in `SecretMessageService` after returning the DTO.
The service does not control when Spring/Jackson finishes writing the HTTP
response.

Acceptance criteria:

- The component that writes the HTTP response also owns final key cleanup.
- A test proves cleanup happens after response serialization.
- The code documents any unavoidable framework-owned copies.

### 4. Prevent Secret Logging

Never log:

- AES keys.
- MIEK.
- Request bodies.
- Response bodies.
- `CreateMessageResponse`.
- Exceptions that include raw request or response payloads.

Add a CI guard that fails if logger calls reference known secret-bearing objects
or obvious secret variable names such as `aesKey`, `secretKey`, `password`,
`token`, or response DTOs. Avoid a naive ban on every variable named `key`;
values such as idempotency keys may be safe to log only if the API policy
explicitly allows it.

Acceptance criteria:

- Integration tests can run with verbose logging and no Base64 key appears in
  captured logs.
- Production does not enable request/response body logging.

### 5. Make Debug Mode Explicitly Non-Production

The Docker entrypoint supports JDWP when `DEBUG=true`. That is useful locally,
but a debugger can inspect heap state and read keys.

Production must not expose JDWP or dynamic attach.

Acceptance criteria:

- Production deployment sets `DEBUG=false`.
- Port `5005` is not published in production.
- Production startup fails fast if both `DEBUG=true` and
  `APP_ENV=production` are present.

## JVM Runtime Hardening Tasks

### 1. Disable Dynamic Attach

Use these production JVM flags:

```text
-XX:+DisableAttachMechanism
-Dcom.sun.management.jmxremote=false
```

`-XX:+DisableAttachMechanism` blocks common tools such as `jmap`, `jstack`, and
dynamic agent loading against the running process.

Acceptance criteria:

- A staging check confirms `jcmd`, `jmap`, and remote JMX cannot attach.

### 2. Disable Heap Dump Creation

Do not run production with:

```text
-XX:+HeapDumpOnOutOfMemoryError
```

Prefer failing closed on OOM:

```text
-XX:OnOutOfMemoryError="kill -9 %p"
```

This sacrifices crash forensics to avoid writing a heap dump full of secrets.

Acceptance criteria:

- Production JVM args do not include `-XX:+HeapDumpOnOutOfMemoryError`.
- Staging OOM validation confirms no heap dump is written.

### 3. Wire JVM Options Into The Container

The entrypoint should support a controlled production option variable, for
example:

```sh
exec java $JAVA_OPTS -jar app.jar
```

Then production can set:

```text
JAVA_OPTS="-XX:+DisableAttachMechanism -Dcom.sun.management.jmxremote=false -XX:OnOutOfMemoryError='kill -9 %p'"
```

Be careful with shell quoting in Docker and Compose. Test the final rendered
command with `docker compose config`.

Acceptance criteria:

- `docker compose exec app ps` shows the hardening flags in the Java command.
- The final deployment manifest includes the same flags.

## OS And Container Hardening Tasks

### 1. Disable Core Dumps

In the container entrypoint or systemd unit:

```sh
ulimit -c 0
```

In Docker Compose:

```yaml
ulimits:
  core: 0
```

Acceptance criteria:

- A staging crash test confirms no core file is produced.

### 2. Disable Swap For The Container

If heap pages are swapped to disk, secrets may remain on disk after the process
exits.

In Docker Compose:

```yaml
mem_limit: 512m
memswap_limit: 512m
```

`memswap_limit == mem_limit` means the container gets no additional swap.

Acceptance criteria:

- Container swap is disabled or bounded to the memory limit.
- Stress testing confirms the process is OOM-killed instead of swapping.

### 3. Restrict `ptrace`

On Linux hosts:

```sh
sysctl -w kernel.yama.ptrace_scope=2
```

In the container:

```yaml
cap_drop:
  - ALL
security_opt:
  - no-new-privileges:true
```

Re-add only the capabilities the service truly needs. A Spring Boot service on
port 8080 usually needs none.

Acceptance criteria:

- The app does not have `CAP_SYS_PTRACE`.
- `/proc/<pid>/mem` cannot be read by another unprivileged process.

### 4. Run As A Non-Root User

The Dockerfile already creates and uses `appuser`. Keep that requirement in
place.

Acceptance criteria:

- `docker compose exec app id` shows a non-root user.
- Runtime manifests do not override the user back to root.

## Verification Plan

Some checks require privileged local or staging access and should run
periodically rather than on every PR.

1. **Heap dump scan**
   - Create a known-marker message/key in a controlled environment.
   - Take a heap dump after the response is flushed.
   - Search for the marker.
   - Fail the check if the marker appears in a long-lived object path.

2. **Logging leak test**
   - Run integration tests with verbose logging.
   - Capture logs.
   - Search for generated AES keys, request bodies, and response bodies.

3. **Runtime attach test**
   - Start the container with production JVM flags.
   - Confirm `jcmd`, `jmap`, and remote JMX cannot attach.

4. **Core dump test**
   - Trigger a controlled JVM crash in staging.
   - Confirm no core file is produced.

5. **Swap test**
   - Stress the container above its memory limit.
   - Confirm it is OOM-killed and does not swap.

6. **Memory leak review**
   - Confirm Redis idempotency records have TTLs.
   - Confirm attempt counters have TTLs.
   - Confirm request size limits are enforced.
   - Confirm NATS subscribers do not accumulate duplicate handlers.

## Implementation Milestones

### Milestone 1: Document The Current Key Flow

Deliverables:

- A short diagram or comment showing the AES key path from generation to HTTP
  response.
- A list of every current `String` copy of the AES key.

### Milestone 2: Convert Internal Key Flow To `byte[]`

Deliverables:

- No service-layer AES key as `String`.
- Key-owning objects implement explicit cleanup.
- Unit tests prove cleanup on success and failure.

### Milestone 3: Harden Response Serialization

Deliverables:

- Base64 encoding happens at the HTTP boundary.
- Cleanup happens after serialization.
- The remaining framework-owned copies are documented.

### Milestone 4: Add Runtime Flags

Deliverables:

- Production Java command includes attach/JMX/heap-dump hardening.
- Debug mode is blocked in production.
- Compose or deployment config disables core dumps, swap, and ptrace.

### Milestone 5: Add Verification

Deliverables:

- Logging leak test.
- Heap dump scan procedure.
- Runtime attach check.
- Memory leak review checklist.

## Review Checklist

Before marking memory hardening complete, answer these questions:

- Where is the per-message AES key generated?
- What object owns the key bytes at each step?
- Who wipes each key buffer?
- Where does the key first become Base64 text?
- Can the key become part of a log line, exception, metric, or trace?
- Can production write heap dumps or core dumps?
- Can production attach a debugger or Java agent?
- Can a non-root process read this process memory?
- Are Redis keys bounded by TTL?
- Are request sizes bounded?
- What verification proves the answers above?

## Out Of Scope

This plan does not:

- Protect the key in transit. That is handled by TLS termination.
- Protect the key on the client after the client receives it.
- Defeat root access on the host.
- Encrypt the JVM heap.
- Provide hardware enclave isolation.
