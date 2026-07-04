# JVM Memory Security Primer

## Purpose

This primer explains the JVM and memory concepts behind
[`MEMORY_HARDENING.md`](MEMORY_HARDENING.md). Read this before implementing the
hardening plan if you are still building intuition around Java memory, garbage
collection, heap dumps, memory leaks, and secret handling.

The goal is not to memorize flags. The goal is to understand why each hardening
control exists.

## Mental Model: Where Data Lives

### Heap

Most Java objects live on the heap: `String`, `byte[]`, DTOs, collections,
Spring beans, request bodies, and many cryptography objects. A heap dump captures
these objects and can expose secrets.

If an AES key becomes a Java object on the heap, assume it may appear in a heap
dump until proven otherwise.

### Stack

Local variables and method frames live on thread stacks, but references on the
stack usually point to objects on the heap. Keeping a key in a local variable
does not mean the key bytes are safely stack-only.

Example:

```java
void useKey() {
    byte[] key = generateKey();
}
```

The variable `key` is local, but the `byte[]` object is still a heap object.

### Native Memory

The JVM and some libraries allocate memory outside the Java heap. Examples
include direct buffers, thread stacks, JVM internals, TLS libraries, compression
buffers, and some IO buffers. Heap dumps do not show all native memory, but core
dumps often do.

### GC Roots

The garbage collector starts from roots: thread stacks, static fields, JNI
references, active classloaders, and live objects reachable from them. Any object
reachable from a GC root is considered live.

If an object is still reachable, GC will not collect it.

### Garbage Collection

GC reclaims unreachable objects eventually. It does not promise immediate
cleanup, and it does not promise to overwrite old bytes. A secret can remain in
heap memory after the Java object is no longer reachable.

This is why "the GC will clean it later" is not a security control.

### Metaspace

Metaspace stores class metadata, not ordinary message keys. Metaspace leaks
usually come from classloader leaks, dynamic proxies, hot reload, or libraries
that keep classloaders reachable. It is less relevant to per-request AES keys,
but important for general JVM memory health.

## Why `String` Is Dangerous For Secrets

`String` is immutable. Once a secret becomes a `String`, application code cannot
wipe its backing storage.

A `String` can also be copied by:

- JSON serialization.
- Logging.
- Validation.
- Request binding.
- Exception messages.
- String concatenation.
- Debuggers.
- Framework internals.

Base64 does not make a key safe. Base64 is only an encoding. A Base64 AES key is
still the AES key.

Prefer `byte[]` or `char[]` for key material because those arrays can be
overwritten with `Arrays.fill(...)` when no longer needed.

Important limitation: zeroing is best-effort. The JVM, JIT, crypto provider,
Base64 encoder, or serializer may have made copies that application code cannot
wipe.

## Secret Handling Rule Of Thumb

For key material:

1. Keep it as bytes internally.
2. Give ownership of each buffer to one component.
3. Wipe buffers in `finally` blocks.
4. Convert to Base64 only at the external protocol boundary.
5. Never log it.
6. Assume heap dumps and debuggers can read it unless blocked.

Example cleanup pattern:

```java
byte[] keyBytes = generateKeyBytes();
try {
    // use keyBytes
} finally {
    Arrays.fill(keyBytes, (byte) 0);
}
```

## Memory Hardening vs Memory Leaks

Memory hardening and memory leak prevention are related, but not the same.

Hardening asks: "Can an attacker read sensitive bytes?"

Leak prevention asks: "Do objects stay reachable longer than intended?"

A memory leak can become a security problem when leaked objects contain secrets
or keep secret-bearing objects reachable.

## Common JVM Memory Leak Patterns

Common leak sources:

- Static maps or caches without size limits.
- `ThreadLocal` values not removed after request handling.
- Executor queues that grow without bounds.
- Listeners, subscribers, or callbacks that are registered but never removed.
- Large request or response bodies retained in logs, exceptions, or metrics.
- Redis, NATS, HTTP, or database client buffers accumulating under backpressure.
- Missing TTLs for Redis keys.
- Classloader leaks in hot-reload or plugin systems.

What to watch in this project:

- Redis idempotency records must always have TTLs.
- Attempt counters must always have TTLs.
- Request body size must stay bounded.
- NATS subscribers must not accumulate duplicate handlers.
- Logs and exceptions must not retain request bodies or keys.

## Garbage Collector Notes

GC choice does not solve secret handling. G1, ZGC, and Shenandoah can all move,
copy, and retain objects in ways application code does not directly control.

Important lessons:

- GC decides when unreachable objects are collected.
- GC does not guarantee memory is overwritten.
- Lower heap pressure can reduce accidental retention time, but it is not a
  security boundary.
- Heap dumps can include objects that are no longer useful to the application but
  are still reachable.
- Tuning GC is mostly about latency and throughput, not secret erasure.

Use GC tuning to keep the service stable under load. Use memory hardening to
reduce key exposure.

## Didactic Exercises

These exercises are for learning and staging validation. Some require privileged
local or staging access, so they should not run on every PR.

### Exercise 1: Find A Secret In A Heap Dump

1. Temporarily add a known marker key or secret in a test branch.
2. Send a create request.
3. Take a heap dump in a controlled local or staging environment.
4. Search the dump for the marker.
5. Refactor one key path from `String` to `byte[]`.
6. Repeat the dump and compare the result.

Learning outcome: understand why `String` and framework copies are dangerous.

### Exercise 2: Compare `String` And `byte[]`

1. Store the same secret once as a `String` and once as a `byte[]`.
2. Wipe the `byte[]` with `Arrays.fill(...)`.
3. Take a heap dump.
4. Search for both values.

Learning outcome: understand why mutable buffers are easier to control than
immutable strings.

### Exercise 3: Prove Logs Do Not Leak Keys

1. Run the integration suite with verbose logging.
2. Capture logs to a file.
3. Search for generated AES keys and request/response bodies.
4. Add a test or CI check for the patterns.

Learning outcome: accidental logging is often easier to exploit than raw memory.

### Exercise 4: Simulate A Memory Leak

1. Create a test-only static `Map<String, byte[]>`.
2. Add one large value per request.
3. Run a load test.
4. Observe heap growth with `jcmd`, actuator metrics, or a profiler.
5. Remove the leak and compare behavior.

Learning outcome: leaks are caused by reachability, not by "GC failing."

### Exercise 5: Validate Runtime Hardening

1. Start the container with production JVM flags.
2. Try to attach with `jcmd` or `jmap`.
3. Confirm attach fails.
4. Trigger an OOM in staging.
5. Confirm no heap dump is written.

Learning outcome: JVM flags must be verified, not assumed.

### Exercise 6: Validate OS Hardening

1. Confirm core dumps are disabled.
2. Confirm the process runs as non-root.
3. Confirm `SYS_PTRACE` is unavailable.
4. Confirm swap is disabled or bounded to the memory limit.

Learning outcome: application code cannot compensate for a permissive host.

## Glossary

- **AES key**: Symmetric key used to encrypt or decrypt one secret message.
- **Base64**: Text encoding for bytes. It is not encryption.
- **Core dump**: OS-level process memory snapshot written after a crash.
- **GC root**: Starting point the garbage collector uses to find live objects.
- **Heap dump**: JVM snapshot of heap objects, often used for debugging memory.
- **JDWP**: Java Debug Wire Protocol. Allows remote debugging and heap access.
- **JMX**: Java Management Extensions. Can expose runtime operations.
- **MIEK**: Master Idempotency Encryption Key, used to encrypt stored AES keys.
- **Metaspace**: JVM memory area for class metadata.
- **OOM**: Out of memory condition.
- **`ptrace`**: Linux mechanism that lets one process inspect/control another.
- **Swap**: Disk-backed memory used when RAM is pressured.
