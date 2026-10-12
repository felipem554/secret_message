// Load profiles, stage windows and thresholds shared by every scenario.
//
// Every profile is a list of load levels. Each level gets a warm-up window
// (ramp to the level, then settle) and a hold window. Only hold windows are
// measured: requests are tagged `phase:measure` + `stage:<name>` while the
// level is held and `phase:warmup` otherwise, so warm-up and ramps never
// pollute the percentiles (SCALE-1: "warm-up 30s, excluded from results").
//
// Env vars (all optional):
//   BASE_URL      default http://localhost:8080
//   PROFILE       steps (default) | breakpoint | smoke
//   STEPS         VU levels for `steps`             default 10,50,200
//   RATES         req-iterations/s for `breakpoint`  default 50,100,200,400,800,1600
//   WARMUP        ramp + settle per level            default 30s
//   HOLD          measured time per level            default 2m
//   MAX_VUS       VU cap for `breakpoint`            default 1000
//   THINK_TIME    seconds of sleep per iteration     default 0 (closed loop, max pressure)

import exec from "k6/execution";

export const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
export const PROFILE = __ENV.PROFILE || "steps";
export const THINK_TIME = parseFloat(__ENV.THINK_TIME || "0");

const WARMUP_S = parseDuration(__ENV.WARMUP || "30s");
const HOLD_S = parseDuration(__ENV.HOLD || "2m");
const RAMP_DOWN_S = 10;

function parseDuration(s) {
  const m = String(s).match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/);
  if (!m) throw new Error(`bad duration: ${s}`);
  const n = parseFloat(m[1]);
  return { ms: n / 1000, s: n, m: n * 60, h: n * 3600 }[m[2] || "s"];
}

function parseList(s) {
  return String(s).split(",").map((x) => parseInt(x.trim(), 10)).filter((x) => x > 0);
}

export const TRACKED_STATUSES = ["0", "200", "201", "400", "404", "409", "413", "429", "500", "503"];

const pad = (n) => String(n).padStart(4, "0");

// Builds the level schedule: [{name, level, start, end}] in seconds from
// scenario start. Shared by the executor config and the per-request tagger.
function buildPlan() {
  switch (PROFILE) {
    case "smoke":
      return { unit: "vus", levels: [1], warmup: 2, hold: 10 };
    case "breakpoint":
      return { unit: "rps", levels: parseList(__ENV.RATES || "50,100,200,400,800,1600"), warmup: WARMUP_S, hold: HOLD_S };
    case "steps":
      return { unit: "vus", levels: parseList(__ENV.STEPS || "10,50,200"), warmup: WARMUP_S, hold: HOLD_S };
    default:
      throw new Error(`unknown PROFILE '${PROFILE}' (steps | breakpoint | smoke)`);
  }
}

export const PLAN = buildPlan();

export const STAGES = PLAN.levels.map((level, i) => {
  const start = i * (PLAN.warmup + PLAN.hold) + PLAN.warmup;
  return { name: `${PLAN.unit}-${pad(level)}`, level, unit: PLAN.unit, start, end: start + PLAN.hold };
});

function executor() {
  const k6Stages = [];
  for (const level of PLAN.levels) {
    k6Stages.push({ duration: `${PLAN.warmup}s`, target: level });
    k6Stages.push({ duration: `${PLAN.hold}s`, target: level });
  }
  k6Stages.push({ duration: `${RAMP_DOWN_S}s`, target: 0 });

  if (PLAN.unit === "rps") {
    // Open model: k6 starts iterations at the target rate whether or not the
    // app keeps up. Achieved rate < target (and dropped_iterations > 0) means
    // the level is past saturation.
    const maxVUs = parseInt(__ENV.MAX_VUS || "1000", 10);
    return {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: Math.min(maxVUs, Math.max(50, PLAN.levels[0] * 2)),
      maxVUs,
      stages: k6Stages,
    };
  }
  // Closed model: N VUs loop as fast as responses come back. Throughput at a
  // level is the app's capacity at that concurrency.
  return { executor: "ramping-vus", startVUs: 0, stages: k6Stages, gracefulRampDown: "10s" };
}

// Call at the top of every iteration. Tags everything the VU emits during the
// iteration (HTTP metrics, checks, custom metrics) with its stage and phase.
export function tagStage() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const stage = STAGES.find((s) => elapsed >= s.start && elapsed < s.end);
  exec.vu.metrics.tags.phase = stage ? "measure" : "warmup";
  exec.vu.metrics.tags.stage = stage ? stage.name : "warmup";
}

/**
 * k6 options for a scenario.
 *
 * Real thresholds (fail the run): <0.1% failed requests and no 429s while
 * measuring, plus every check passing. A 429 aborts the run early: it means
 * the rate limit was not raised and every later number would be meaningless.
 *
 * The `*>=0` thresholds always pass. They exist only to make k6 compute the
 * per-stage / per-endpoint sub-metrics that lib/summary.js reports.
 */
export function buildOptions(scenarioName, endpoints) {
  const thresholds = {
    "http_req_failed{phase:measure}": ["rate<0.001"],
    "checks{phase:measure}": ["rate>0.999"],
    rate_limited: [{ threshold: "count==0", abortOnFail: true, delayAbortEval: "5s" }],
    "http_req_duration{phase:measure}": ["max>=0"],
  };
  for (const e of endpoints) {
    thresholds[`http_req_duration{phase:measure,endpoint:${e}}`] = ["max>=0"];
  }
  // status 0 = connection-level failure (refused/reset/timeout), e.g. JVM gone.
  for (const st of TRACKED_STATUSES) {
    thresholds[`unexpected_status{status:${st}}`] = ["count>=0"];
  }
  for (const s of STAGES) {
    thresholds[`http_req_duration{stage:${s.name}}`] = ["max>=0"];
    thresholds[`http_reqs{stage:${s.name}}`] = ["count>=0"];
    thresholds[`http_req_failed{stage:${s.name}}`] = ["rate>=0"];
    thresholds[`iterations{stage:${s.name}}`] = ["count>=0"];
    if (endpoints.length > 1) {
      for (const e of endpoints) {
        thresholds[`http_req_duration{stage:${s.name},endpoint:${e}}`] = ["max>=0"];
      }
    }
  }

  return {
    scenarios: { [scenarioName]: executor() },
    thresholds,
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max", "count"],
  };
}
