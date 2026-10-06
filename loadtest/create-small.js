// Scenario (a) create-small: POST /api/v1/messages with a ~200 B message.
// Measures the create path alone: rate-limit CAS + AES encrypt + Redis SET.
//
//   ./run.sh create-small                    # steps 10,50,200 VUs
//   PROFILE=breakpoint ./run.sh create-small # find max req/s

import { check } from "k6";
import { buildOptions, tagStage } from "./lib/config.js";
import { createMessage, messageBody, parseJson, payload, preflight, think } from "./lib/api.js";
import { makeHandleSummary } from "./lib/summary.js";

const SCENARIO = "create-small";
const BODY = messageBody(payload(parseInt(__ENV.MESSAGE_BYTES || "200", 10)));

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
  think();
}
