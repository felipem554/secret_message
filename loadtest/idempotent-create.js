// Scenario (d) idempotent-create: the same Idempotency-Key + body sent 3x.
// First call creates (201); the two retries must replay (200, duplicate:true)
// with the same messageId and aesKey, i.e. exactly one message per key.
// Exercises the idempotency path: SHA-256 of the body, record GET, MIEK
// decrypt of the stored key.
//
// Latency is split into `create` (first call) and `create-replay` (retries).
//
//   ./run.sh idempotent-create

import { check } from "k6";
import { buildOptions, tagStage } from "./lib/config.js";
import { createMessage, messageBody, parseJson, payload, preflight, think, uuidv4 } from "./lib/api.js";
import { makeHandleSummary } from "./lib/summary.js";

const SCENARIO = "idempotent-create";
const BODY = messageBody(payload(parseInt(__ENV.MESSAGE_BYTES || "200", 10)));
const RETRIES = 2;

export const options = buildOptions(SCENARIO, ["create", "create-replay"]);
export const setup = preflight;
export const handleSummary = makeHandleSummary(SCENARIO);

export default function () {
  tagStage();
  const key = uuidv4();

  const first = createMessage(BODY, { idempotencyKey: key });
  const f = parseJson(first);
  const firstOk = check(first, {
    "first create 201": (r) => r.status === 201,
    "first create returns id+key": () => !!(f && f.messageId && f.aesKey),
  });
  if (!firstOk) return;

  for (let i = 0; i < RETRIES; i++) {
    const replay = createMessage(BODY, { idempotencyKey: key, endpoint: "create-replay", expected: [200] });
    const r = parseJson(replay);
    check(replay, {
      "replay 200": (res) => res.status === 200,
      "replay duplicate:true": () => !!(r && r.duplicate === true),
      "replay same message": () => !!(r && r.messageId === f.messageId && r.aesKey === f.aesKey),
    });
  }
  think();
}
