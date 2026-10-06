// Scenario (c) create-1mb: max-size creates.
//
// The controller rejects a request whose *Content-Length* exceeds
// app.max-message-size (1 MiB), so the message is 1 MiB minus the JSON
// wrapper `{"message":""}` (14 B): the largest body the API accepts.
//
// Expected on the baseline image: the 128 MB SerialGC heap fills and
// -XX:+ExitOnOutOfMemoryError kills the JVM at higher VU levels. Record that
// as a result (SCALE-2), it is the regression SCALE-7 fixes. Afterwards the
// report shows conn-error responses; run.sh reports whether the app exited.
//
// Cleanup: each message is revealed right after it is created. Never-revealed
// 1 MiB secrets live 2 days in Redis, so a few minutes at 50 VUs would fill
// the host's RAM and the run would measure Redis, not the app. The reveal is
// tagged phase/stage=cleanup, so it is excluded from every reported number,
// but it still costs app CPU. CLEANUP=0 turns it off (then FLUSHALL after).
//
// k6 memory: each VU holds its own copy of the body (~2 MB as a JS string),
// so 200 VUs need ~0.5 GB on the load generator.
//
//   STEPS=10,50,200 ./run.sh create-1mb

import { check } from "k6";
import http from "k6/http";
import { BASE_URL, buildOptions, tagStage } from "./lib/config.js";
import { createMessage, messageBody, parseJson, payload, preflight, think } from "./lib/api.js";
import { makeHandleSummary } from "./lib/summary.js";

const SCENARIO = "create-1mb";
const MAX = parseInt(__ENV.MAX_MESSAGE_SIZE || "1048576", 10);
const WRAPPER = messageBody("").length;
const BODY = messageBody(payload(parseInt(__ENV.MESSAGE_BYTES || String(MAX - WRAPPER), 10)));
const CLEANUP = __ENV.CLEANUP !== "0";
const CLEANUP_TAGS = { endpoint: "cleanup", stage: "cleanup", phase: "cleanup", name: "cleanup reveal" };

export const options = buildOptions(SCENARIO, ["create"]);
export const setup = preflight;
export const handleSummary = makeHandleSummary(SCENARIO);

export default function () {
  tagStage();
  const res = createMessage(BODY);
  const body = parseJson(res);
  check(res, {
    "create 201": (r) => r.status === 201,
    "create returns id+key": () => !!(body && body.messageId && body.aesKey),
  });
  if (CLEANUP && body && body.messageId) {
    http.post(`${BASE_URL}/api/v1/messages/reveal`, JSON.stringify({ messageId: body.messageId, aesKey: body.aesKey }), {
      headers: { "Content-Type": "application/json" },
      tags: CLEANUP_TAGS,
      responseType: "none",
    });
  }
  think();
}
