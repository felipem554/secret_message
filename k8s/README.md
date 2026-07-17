# Kubernetes manifests

Plain manifests with Kustomize overlays. Full guide: [`docs/KUBERNETES.md`](../docs/KUBERNETES.md).

```
base/               # app + redis + nats + network policies (namespace-agnostic)
overlays/dev/       # namespace secret-message-dev, 1 app replica
overlays/prod/      # namespace secret-message, 2 app replicas, Ingress, APP_ENV=production
```

## Quick start

```bash
# 1. Provide secrets (gitignored; never commit secrets.env)
cd k8s/overlays/dev
cp secrets.env.example secrets.env
$EDITOR secrets.env          # IDEMPOTENCY_MASTER_KEY: openssl rand -base64 32

# 2. Render and review
kubectl kustomize .

# 3. Apply
kubectl apply -k .

# 4. Reach the app (port-forward bypasses NetworkPolicy — dev only)
kubectl -n secret-message-dev port-forward svc/secret-message 8080:8080
curl http://localhost:8080/actuator/health
```

## Design notes (short version)

- **Redis has no persistence** (`emptyDir`, RDB/AOF disabled): stored data is
  TTL-bound ciphertext for self-destructing messages; not writing it to a
  PersistentVolume is consistent with the security model. A Redis pod restart
  loses stored messages. If durability is ever required, switch to a
  StatefulSet + PVC and re-enable AOF — and accept ciphertext at rest.
- **NetworkPolicies are default-deny**: only the app can reach Redis (6379)
  and NATS (4222); only the ingress-controller namespace can reach the app
  (8080). This enforces ADR-0002's rule that the internal NATS interface
  stays off public networks. Adjust the `ingress-nginx` namespace selector in
  `base/network-policies.yaml` to your controller. Requires a CNI that
  enforces NetworkPolicy (Calico, Cilium; kind's default CNI does not).
- **Pod hardening mirrors compose/Dockerfile**: non-root uid 10001,
  `allowPrivilegeEscalation: false`, all capabilities dropped, read-only root
  filesystem, seccomp `RuntimeDefault`, 512Mi memory limit. Core dumps are
  disabled by `docker-entrypoint.sh` (`ulimit -c 0`) since pods can't set ulimits.
- **No CloudNativePG / Postgres**: there is no PostgreSQL in this stack —
  Redis is the datastore by design (ADR-0001). See `docs/KUBERNETES.md`.
