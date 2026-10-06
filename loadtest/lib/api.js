// HTTP helpers for the public API (/api/v1/messages). Every request carries an
// `endpoint` tag so latency can be split per endpoint in the summary.

import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Counter } from "k6/metrics";
import { BASE_URL, THINK_TIME } from "./config.js";

// 429s mean the limiter was not raised for the test (see README). Counted
// separately so the run aborts instead of reporting rate-limiter numbers.
export const rateLimited = new Counter("rate_limited");
// Any response whose status the scenario did not expect, tagged with status.
export const unexpectedStatus = new Counter("unexpected_status");

const JSON_HEADERS = { "Content-Type": "application/json" };

function track(res, expected) {
  if (res.status === 429) rateLimited.add(1);
  if (!expected.includes(res.status)) unexpectedStatus.add(1, { status: String(res.status) });
  return res;
}

export function createMessage(body, { idempotencyKey, endpoint = "create", expected = [201] } = {}) {
  const headers = idempotencyKey ? { ...JSON_HEADERS, "Idempotency-Key": idempotencyKey } : JSON_HEADERS;
  const res = http.post(`${BASE_URL}/api/v1/messages`, body, {
    headers,
    tags: { endpoint, name: "POST /api/v1/messages" },
    responseCallback: http.expectedStatuses(...expected),
  });
  return track(res, expected);
}

export function revealMessage(messageId, aesKey, { expected = [200] } = {}) {
  const res = http.post(`${BASE_URL}/api/v1/messages/reveal`, JSON.stringify({ messageId, aesKey }), {
    headers: JSON_HEADERS,
    tags: { endpoint: "reveal", name: "POST /api/v1/messages/reveal" },
    responseCallback: http.expectedStatuses(...expected),
  });
  return track(res, expected);
}

export function messageBody(text) {
  return JSON.stringify({ message: text });
}

// Deterministic printable payload of exactly `bytes` ASCII bytes.
export function payload(bytes) {
  const unit = "secret-message-load-test-0123456789-";
  return unit.repeat(Math.ceil(bytes / unit.length)).slice(0, bytes);
}

export function uuidv4() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function parseJson(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

export function think() {
  if (THINK_TIME > 0) sleep(THINK_TIME);
}

/**
 * Pre-flight run once before the load starts:
 * - the app is UP;
 * - the per-IP rate limit was raised (APP_RATELIMIT_REQUESTSPERDAY), read from
 *   X-RateLimit-Remaining, so the run cannot silently measure 429s.
 * The probe message is revealed so it does not linger in Redis.
 */
export function preflight() {
  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { endpoint: "preflight" } });
  if (health.status !== 200) {
    fail(`app not healthy at ${BASE_URL}/actuator/health (status ${health.status})`);
  }

  const minRemaining = parseInt(__ENV.MIN_RATE_LIMIT_REMAINING || "1000000", 10);
  const res = http.post(`${BASE_URL}/api/v1/messages`, messageBody("preflight"), {
    headers: JSON_HEADERS,
    tags: { endpoint: "preflight" },
  });
  const remaining = parseInt(res.headers["X-Ratelimit-Remaining"] || "-1", 10);
  if (res.status === 429 || remaining < minRemaining) {
    fail(
      `rate limit too low for a load test (status ${res.status}, X-RateLimit-Remaining=${remaining}). ` +
        `Restart the app with APP_RATELIMIT_REQUESTSPERDAY=100000000 (see loadtest/README.md), ` +
        `or set MIN_RATE_LIMIT_REMAINING to override.`
    );
  }
  const created = parseJson(res);
  check(created, { "preflight create ok": (b) => b && b.messageId && b.aesKey });
  if (created && created.messageId) {
    http.post(`${BASE_URL}/api/v1/messages/reveal`, JSON.stringify(created), {
      headers: JSON_HEADERS,
      tags: { endpoint: "preflight" },
    });
  }
  return { rateLimitRemaining: remaining, startedAt: Date.now() };
}
