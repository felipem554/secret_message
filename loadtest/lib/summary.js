// End-of-test report: per-stage throughput and p50/p90/p95/p99 latency, plus
// paste-ready rows for the results log in docs/SCALING_TICKETS.md.
//
// Writes:
//   stdout                                   human-readable report
//   <RESULTS_DIR>/<run>.json  raw k6 summary + stage plan
//   <RESULTS_DIR>/<run>.txt   copy of the stdout report (run.sh appends container stats)
// <run> is RUN_NAME from run.sh (<ts>-[<label>-]<scenario>-<profile>), so it
// matches the -stats.csv and -env.txt files of the same run.

import { BASE_URL, PROFILE, STAGES, TRACKED_STATUSES } from "./config.js";

const RESULTS_DIR = __ENV.RESULTS_DIR || "results";

function fmt(n, digits = 1) {
  if (n === undefined || n === null || Number.isNaN(n)) return "-";
  return n.toFixed(digits);
}

function table(headers, rows) {
  const widths = headers.map((h, i) => Math.max(h.length, ...rows.map((r) => String(r[i]).length)));
  const line = (cells) => cells.map((c, i) => String(c).padStart(widths[i])).join("  ");
  return [line(headers), widths.map((w) => "-".repeat(w)).join("  "), ...rows.map(line)].join("\n");
}

function vals(data, name) {
  const m = data.metrics[name];
  return m ? m.values : {};
}

// Reads one Trend sub-metric into {count, p50, p90, p95, p99, max}.
function latency(data, name) {
  const v = vals(data, name);
  return { count: v.count, p50: v.med, p90: v["p(90)"], p95: v["p(95)"], p99: v["p(99)"], max: v.max, avg: v.avg };
}

function stageRows(data) {
  return STAGES.map((s) => {
    const hold = s.end - s.start;
    const reqs = vals(data, `http_reqs{stage:${s.name}}`).count || 0;
    const iters = vals(data, `iterations{stage:${s.name}}`).count || 0;
    const failed = vals(data, `http_req_failed{stage:${s.name}}`).rate;
    const l = latency(data, `http_req_duration{stage:${s.name}}`);
    return { stage: s, reqs, rps: reqs / hold, ips: iters / hold, failedPct: (failed || 0) * 100, ...l };
  });
}

function endpointsOf(data) {
  const re = /^http_req_duration\{phase:measure,endpoint:([^}]+)\}$/;
  return Object.keys(data.metrics).map((k) => (k.match(re) || [])[1]).filter(Boolean);
}

export function buildReport(scenario, data) {
  const out = [];
  const date = new Date().toISOString();
  const gitSha = __ENV.GIT_SHA || "-";
  const unitLabel = STAGES.length && STAGES[0].unit === "rps" ? "target it/s" : "VUs";

  out.push("");
  out.push(`=== secret_message load test: ${scenario} ===`);
  out.push(`profile=${PROFILE}  base=${BASE_URL}  git=${gitSha}  date=${date}`);
  if (__ENV.LABEL) out.push(`label: ${__ENV.LABEL}`);
  if (__ENV.NOTE) out.push(`note: ${__ENV.NOTE}`);
  out.push("Latencies in ms, measured only during each hold window (warm-up/ramps excluded).");
  out.push("");

  const rows = stageRows(data);
  out.push("Per stage (all endpoints):");
  out.push(
    table(
      ["stage", unitLabel, "requests", "req/s", "iter/s", "p50", "p90", "p95", "p99", "max", "err%"],
      rows.map((r) => [
        r.stage.name, r.stage.level, r.reqs, fmt(r.rps), fmt(r.ips),
        fmt(r.p50), fmt(r.p90), fmt(r.p95), fmt(r.p99), fmt(r.max), fmt(r.failedPct, 2),
      ])
    )
  );

  const endpoints = endpointsOf(data);
  if (endpoints.length > 1) {
    out.push("");
    out.push("Per stage and endpoint:");
    const epRows = [];
    for (const s of STAGES) {
      for (const e of endpoints) {
        const l = latency(data, `http_req_duration{stage:${s.name},endpoint:${e}}`);
        const hold = s.end - s.start;
        epRows.push([s.name, e, l.count || 0, fmt((l.count || 0) / hold), fmt(l.p50), fmt(l.p90), fmt(l.p95), fmt(l.p99), fmt(l.max)]);
      }
    }
    out.push(table(["stage", "endpoint", "requests", "req/s", "p50", "p90", "p95", "p99", "max"], epRows));
  }

  out.push("");
  const all = latency(data, "http_req_duration{phase:measure}");
  out.push(
    `All measured windows: requests=${all.count || 0}  p50=${fmt(all.p50)}  p90=${fmt(all.p90)}  ` +
      `p95=${fmt(all.p95)}  p99=${fmt(all.p99)}  max=${fmt(all.max)}`
  );
  const best = rows.reduce((a, r) => (r.rps > (a ? a.rps : -1) ? r : a), null);
  if (best) out.push(`Peak throughput: ${fmt(best.rps)} req/s (${fmt(best.ips)} iter/s) at ${best.stage.name}`);
  if (vals(data, "dropped_iterations").count) {
    out.push(`dropped_iterations=${vals(data, "dropped_iterations").count} (open model could not keep the target rate: past saturation or MAX_VUS too low)`);
  }

  const statuses = TRACKED_STATUSES
    .map((st) => [st, vals(data, `unexpected_status{status:${st}}`).count || 0])
    .filter(([, c]) => c > 0);
  const totalUnexpected = vals(data, "unexpected_status").count || 0;
  out.push("");
  out.push(
    `Unexpected responses: ${totalUnexpected}` +
      (statuses.length ? `  (${statuses.map(([st, c]) => `${st === "0" ? "conn-error" : st}=${c}`).join(", ")})` : "")
  );
  out.push(`429 rate-limited: ${vals(data, "rate_limited").count || 0}`);

  const checks = vals(data, "checks");
  if (checks.rate !== undefined) {
    out.push(`checks: ${fmt(checks.rate * 100, 2)}% passed (${checks.passes} ok / ${checks.fails} failed)`);
  }

  // Real thresholds only; the "*>=0" ones exist just to materialise sub-metrics.
  const thresholdLines = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    for (const [expr, t] of Object.entries(m.thresholds || {})) {
      if (/>=0$/.test(expr)) continue;
      thresholdLines.push(`  ${t.ok ? "PASS" : "FAIL"}  ${name}: ${expr}`);
    }
  }
  out.push("Thresholds:");
  out.push(...thresholdLines);

  out.push("");
  out.push("Results-log rows (docs/SCALING_TICKETS.md; fill CPU, Jedis, GC, OOM, notes):");
  for (const r of rows) {
    out.push(
      `| ${__ENV.LABEL || ""} | ${date.slice(0, 10)} | | ${gitSha} | ${scenario} | ${r.stage.level}${r.stage.unit === "rps" ? " it/s" : ""} | ` +
        `${__ENV.N_PROCS || 1} | ${fmt(r.rps)} | ${fmt(r.p50)} | ${fmt(r.p95)} | ${fmt(r.p99)} | ${fmt(r.failedPct, 2)} | | | | | p90=${fmt(r.p90)} |`
    );
  }
  out.push("");
  return { text: out.join("\n"), rows, date };
}

export function makeHandleSummary(scenario) {
  return function handleSummary(data) {
    const { text, rows, date } = buildReport(scenario, data);
    const stamp = date.replace(/[:.]/g, "-").slice(0, 19);
    const base = `${RESULTS_DIR}/${__ENV.RUN_NAME || `${stamp}-${scenario}-${PROFILE}`}`;
    const json = {
      scenario,
      profile: PROFILE,
      baseUrl: BASE_URL,
      gitSha: __ENV.GIT_SHA || null,
      label: __ENV.LABEL || null,
      note: __ENV.NOTE || null,
      date,
      // Absolute epoch-ms windows let run.sh line up docker stats with stages.
      stages: rows.map((r) => ({
        name: r.stage.name, unit: r.stage.unit, level: r.stage.level,
        startMs: data.setup_data ? data.setup_data.startedAt + r.stage.start * 1000 : null,
        endMs: data.setup_data ? data.setup_data.startedAt + r.stage.end * 1000 : null,
        requests: r.reqs, reqPerSec: r.rps, iterPerSec: r.ips, errorPct: r.failedPct,
        p50: r.p50, p90: r.p90, p95: r.p95, p99: r.p99, max: r.max,
      })),
      k6: data,
    };
    return {
      stdout: text,
      [`${base}.json`]: JSON.stringify(json, null, 2),
      [`${base}.txt`]: text,
    };
  };
}
