# Memory Hardening Plan

## Goal

The HTTP API generates AES-256 keys server-side and returns them to the client. Between generation and the response being flushed, the key exists in JVM memory. This document defines the controls that minimize the window of exposure and prevent that key from leaking via heap dumps, core dumps, swap, debugger attachment, or accidental logging.

## Threat model

We assume an attacker who can do **one** of the following:

1. Trigger or read a JVM heap dump (`jmap`, `kill -3`, OOM auto-dump, JMX `HotSpotDiagnosticMXBean`).
2. Trigger or read an OS core dump.
3. Read process memory directly (`ptrace`, `/proc/<pid>/mem`).
4. Read swap-backed pages from disk after the process has terminated.
5. Read application logs.

We **do not** defend against:

- An attacker with root on the host who can read live memory at will. No userspace control defeats this; that is an infrastructure problem.
- Side-channel attacks (cache timing, Spectre-class).

The realistic ceiling is "raise the cost from trivial to requires serious system access." This plan targets that ceiling.

---

## Application-layer controls

### 1. Never store key material in `String`

`String` is immutable in Java. Once a key lands in a `String`, it cannot be zeroed and lives in the heap until garbage collected (and possibly longer, if it ends up interned). All key material must be carried as `byte[]` or `char[]`.

Refactor:

- `SecretMessageIdentifier.aeskey` → `byte[]` internally; expose Base64-encoded `String` only at the JSON boundary.
- `CryptoUtil.encryptMessage(String, String)` → `encryptMessage(byte[] plaintext, byte[] key)`. Plaintext can stay `String` for now since it is the user's payload, not the credential.

### 2. Zero buffers immediately after use

Wrap every key-handling call site in a `try { ... } finally { Arrays.fill(keyBytes, (byte) 0); }` block. Concretely:

- Inside `SecretMessageService.createSecretMessage`, after the response has been serialized and written to the output stream, zero the source buffer.
- Inside `CryptoUtil.encryptMessage` / `decryptMessage`, zero any local copy of the key after the `Cipher` operation completes.
- Avoid passing the key into `ByteBuffer.wrap` without controlling the lifetime of the backing array.

### 3. Custom Jackson serializer for the response

Standard Jackson serialization copies the source `byte[]` into intermediate buffers as it Base64-encodes. Those intermediates are not under our control and are not zeroed.

Replace the response DTO's serializer with one that:

1. Base64-encodes directly into the HTTP response stream's buffer.
2. Zeros both the source key bytes and the encoded buffer immediately after `flush()`.

This keeps the key bytes in exactly one place at a time and guarantees they are wiped before the request handler returns.

### 4. Logging audit and redaction

- CI lint rule that fails the build if any logger statement references a variable named `key`, `aesKey`, `secret`, `password`, or the response DTO type. Use ArchUnit or a simple Checkstyle pattern.
- Logback filter that redacts known field names from any structured log event, as a defense-in-depth fallback.
- No request/response body logging in production. If a debug-level body logger exists, gate it behind a profile that is never active in production.

### 5. Disable JVM agent attachment

JMX and Java agents can read arbitrary heap state. Production JVM flags:

```
-XX:+DisableAttachMechanism
-Dcom.sun.management.jmxremote=false
```

`-XX:+DisableAttachMechanism` blocks `jmap`, `jstack`, and dynamic agent loading on the running process.

---

## JVM-layer controls

### 6. Disable heap dumps in production

Remove `-XX:+HeapDumpOnOutOfMemoryError` from production JVM args. Set:

```
-XX:OnOutOfMemoryError="kill -9 %p"
```

so the process aborts on OOM without writing a heap dump file. This loses crash forensics — that is the trade. Heap dumps are the single largest exposure vector and the JVM offers no way to scrub key material before writing one.

### 7. Disable HotSpot diagnostic MBean

The `HotSpotDiagnosticMXBean.dumpHeap` operation can write a heap dump on demand. Implicitly disabled by `-XX:+DisableAttachMechanism` above, but worth verifying in a staging run.

---

## OS-layer controls

### 8. Disable core dumps

In the container entrypoint or systemd unit:

```
ulimit -c 0
```

Or for Docker:

```
docker run --ulimit core=0 ...
```

Or in `docker-compose.yml`:

```yaml
ulimits:
  core: 0
```

### 9. Disable swap or bound it to zero

If the JVM heap is paged to swap, the pages persist on disk after the process exits. Either disable swap on the host (`swapoff -a`) or bound the container's swap to its memory limit:

```yaml
mem_limit: 512m
memswap_limit: 512m
```

`memswap_limit == mem_limit` means zero swap available to the container.

### 10. Restrict ptrace and `/proc` access

On the host kernel:

```
sysctl -w kernel.yama.ptrace_scope=2
```

`ptrace_scope=2` requires `CAP_SYS_PTRACE` for any cross-process ptrace, blocking standard memory-reading attacks from a compromised non-root user.

In the container:

```yaml
cap_drop:
  - SYS_PTRACE
```

### 11. Run as unprivileged user

The Spring Boot container must not run as root. Add to the Dockerfile:

```dockerfile
RUN addgroup --system app && adduser --system --ingroup app app
USER app
```

Combined with `cap_drop: [ALL]` and explicit re-add of only `NET_BIND_SERVICE` if needed.

---

## Verification

Hardening is invisible until it is tested. Add to the staging pipeline:

1. **Heap dump scan.** After a request that creates a known-secret message, force a heap dump (`jmap` from a privileged side container) and `grep` for the secret pattern. The secret should not appear after the response is flushed. Fail the pipeline if it does.
2. **Core dump test.** Trigger a `SIGSEGV` on the JVM. Confirm no core file is produced.
3. **Swap test.** Stress the container above its memory limit. Confirm it is OOM-killed and no swap file grows on the host.
4. **Logging test.** Run the integration suite with `TRACE` logging enabled. `grep` the captured logs for any Base64 key pattern. Fail the suite if found.

These run periodically, not per-PR — they need privileged container access — but they are the only honest validation that the application-layer controls are working.

---

## What this plan does not do

- It does not protect the key in transit. That is TLS termination at the reverse proxy, covered separately.
- It does not protect the key on the **client's** machine after they receive it. That is the client's responsibility; the API contract makes clear the key is one-shot and must be discarded after use.
- It does not protect against an attacker with `CAP_SYS_PTRACE` or root inside the container. Container escape and privilege escalation are out of scope for this document.
- It does not encrypt the heap itself. That would require a JVM with hardware-enclave support (Intel SGX, AWS Nitro Enclaves), which is a much larger architectural decision than this service warrants.

The plan assumes that "key in cleartext in JVM memory for a few milliseconds, with no path for an unprivileged attacker to read it" is the correct security posture for this service. If that assumption changes — for example, if the service moves to handle higher-classification material — the right next step is enclaves, not more zeroing.
