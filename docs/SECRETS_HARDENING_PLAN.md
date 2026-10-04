# Secrets Hardening Plan

Status: proposed · Scope: production secrets for the app, Redis, NATS and CI

## 1. Inventory

Production secrets live in `k8s/overlays/prod/secrets.env` (gitignored). `secretGenerator` turns that file into the Kubernetes Secret `secret-message-secrets`, and the pods read it through `secretKeyRef`.

| Secret | Consumers | Protects | Impact if leaked |
|---|---|---|---|
| `IDEMPOTENCY_MASTER_KEY` (MIEK) | app | Per-message AES keys inside `idempotency:*` records | **High**: together with a Redis dump, it decrypts every message created with an `Idempotency-Key` that is still within its TTL |
| `REDIS_PASSWORD` | app, redis | Ciphertext, attempt counters, idempotency records, rate-limit state | Medium: read and delete everything, `FLUSHALL`, `CONFIG` |
| `NATS_USER` / `NATS_PASS` | app, nats, internal clients | The `save.msg` / `receive.msg` bus | Medium-high: `receive.msg` carries the AES key and the plaintext |
| `secret-message-tls` | ingress | Public TLS certificate | Managed outside the repo |
| `GITHUB_TOKEN` (CI only) | `docker.yml` | Push to GHCR (`packages: write`) | Indirect: a malicious image becomes prod, because prod pulls `:latest` |

GitHub Actions secrets, variables and environments: **none configured**. Nothing currently deploys from CI.

## 2. Current strengths

- Git history holds only placeholders (`change-this-…`, `redispassword`, `CHANGE_ME`).
- Secret scanning and push protection are enabled on the (public) repo, and the default workflow token is read-only.
- Startup fails fast when `APP_ENV=production` and the dev MIEK is in use, or when `DEBUG=true` is set in production.
- Pods use `secretKeyRef` and default-deny NetworkPolicies, run without a service-account token on a read-only root filesystem, and Redis has no persistence.
- Key material is handled with memory hardening (`docs/MEMORY_HARDENING.md`).

## 3. Gaps (highest risk first)

1. **Manual, laptop-held secrets.** `secrets.env` is plaintext on the operator's disk. Kubernetes Secrets are only base64 in etcd unless the cluster encrypts them at rest. There is no rotation, no audit trail, and a single holder.
2. **No TLS between app ↔ Redis ↔ NATS.** Passwords, ciphertext and `receive.msg` payloads (AES key and plaintext) cross the pod network in the clear.
3. **Passwords on command lines.** `redis-server --requirepass $(REDIS_PASSWORD)` and `nats --pass $(NATS_PASS)` expand into process arguments, which anyone with node or `exec` access can read through `ps` or `/proc/<pid>/cmdline`.
4. **Supply chain into prod.** Actions are pinned by tag rather than by SHA, and the build job has `packages: write`. Prod pulls `:latest` with `imagePullPolicy: Always`. A compromised action could ship an image that receives all prod secrets.
5. **Weak fallbacks fail open.** Compose defaults to `redispassword`, `natspassword` and the dev MIEK. The guard only fires when `APP_ENV=production` is set explicitly, and there is no guard at all for Redis or NATS defaults. Compose publishes Redis `6379` and NATS `8222` on all host interfaces.
6. **One Redis superuser.** The app authenticates as `default`, which can run every command.
7. **No MIEK rotation procedure** and no key ID in idempotency records.
8. **GitHub settings.** Dependabot security updates and non-provider secret-scanning patterns are disabled.

## 4. Plan

### Phase 1: repo-only changes (no infrastructure, ~1 day)

| # | Change | Where |
|---|---|---|
| 1.1 | Pin every `uses:` to a full commit SHA (keep the tag as a comment); Dependabot keeps them updated | `.github/workflows/*.yml` |
| 1.2 | Scope `packages: write` to non-PR events (split the job, or skip login and push on PRs, which it already does) and add explicit `permissions: contents: read` to `test` | `docker.yml` |
| 1.3 | Pin the prod image by digest via the `images:` transformer; drop `imagePullPolicy: Always` in prod | `k8s/overlays/prod/kustomization.yaml` |
| 1.4 | Bind compose Redis and NATS ports to `127.0.0.1:` and remove the `8222` publish in `compose.ghcr.yaml` | `compose*.yaml` |
| 1.5 | Enable Dependabot security updates and non-provider secret-scanning patterns | GitHub repo settings |
| 1.6 | Sign images with cosign (keyless, GitHub OIDC); later, verify signatures at admission | `docker.yml` |

### Phase 2: fail closed (~1 day)

- Refuse to start unless `APP_ENV` is set explicitly. Allow the dev MIEK, an empty Redis password or `redispassword`/`natspassword` **only** when `APP_ENV=development`. Extend `IdempotencyKeyVault`'s guard into a small `ProductionSecretsGuard` that covers all three secrets.
- Drop the `:-default` fallbacks from `compose.ghcr.yaml` (the "run the published image" path) so a missing `.env` fails loudly.
- Tests: extend `IdempotencyKeyVaultProductionGuardTest`-style coverage to the Redis and NATS defaults.

### Phase 3: take secrets off the command line, apply least privilege (~2 days)

- Redis: mount `users.acl` from a Secret (`--aclfile /etc/redis/users.acl`). Disable `default`, and create an `app` user allowed only `~messages:* ~attempts:* ~idempotency:* ~ratelimit:*` and the commands the app uses (`get set del incr expire pexpire eval evalsha` etc.), with `-@dangerous` (removes `FLUSHALL`, `CONFIG`, `KEYS`, …).
- NATS: mount `nats.conf` from a Secret with per-user permissions. The app may subscribe to `save.msg`/`receive.msg` and publish to `_INBOX.>`; internal clients get the reverse. Use bcrypt-hashed passwords in the config, or move to NKeys.
- Verify: `kubectl exec … -- cat /proc/1/cmdline` shows no secret.

### Phase 4: encrypt internal traffic (~2–3 days)

- Install cert-manager with an internal CA `Issuer`. Issue certificates for `redis` and `nats`, and use cert-manager for `secret-message-tls` too.
- Redis: `--tls-port 6379 --port 0`. App: `spring.data.redis.ssl.enabled=true` with the CA truststore.
- NATS: a `tls {}` block. App: `Options.Builder().secure()` / `sslContext`.
- Confirm etcd encryption at rest is enabled (`EncryptionConfiguration`, ideally a KMS provider), or use a managed cluster that does this.

### Phase 5: secrets management and rotation (~1 week)

Pick one:

- **External Secrets Operator + cloud secret manager** (recommended for a cloud cluster). The source of truth lives in AWS Secrets Manager, GCP Secret Manager or Vault, and an `ExternalSecret` replaces `secretGenerator`. You get audit logs, IAM-scoped access and rotation, and no laptop copy.
- **SOPS + age/KMS** (fits a GitOps or small setup). Commit an encrypted `secrets.enc.env`, decrypted at apply time with KSOPS or Flux/Argo.

Then:

- **Rotation runbook:**
  - Redis: ACL users let you add the new password next to the old one, roll the app, then remove the old one.
  - NATS: same pattern with two users.
  - MIEK: see the next item.
- **MIEK versioning:** prefix stored records with a key ID (`v1:`), hold `{current, previous}` keys, encrypt with current and decrypt with either. Drop `previous` after `auto-delete-days`. This gives zero-downtime rotation without losing in-flight idempotency records.

### Should we use GitHub secrets?

Only for **deployment credentials**, and only if CD moves into Actions:

- Create a `production` **Environment** with required reviewers and a branch restriction to `main`/tags.
- Authenticate to the cloud or cluster with **OIDC federation**. Store no long-lived kubeconfig.
- Keep the four app secrets **out** of GitHub. Copying them through a runner adds an exposure point with no benefit, because the cluster pulls them from the secret manager (Phase 5).

## 5. Verification checklist

- [ ] `git log -p --all | grep -E 'PASSWORD=|MASTER_KEY='` shows placeholders only (run in CI with gitleaks)
- [ ] App refuses to start with any default secret unless `APP_ENV=development`
- [ ] No secret appears in `/proc/*/cmdline` of any pod
- [ ] `redis-cli --user app … FLUSHALL` → `NOPERM`
- [ ] Redis and NATS reject plaintext connections
- [ ] Prod image is referenced by digest and its signature verifies
- [ ] Every secret has a documented owner, location and rotation date
