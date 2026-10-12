// Scenario (b) create-reveal: create, then reveal with the returned key.
// One iteration = one secret's full life (2 requests). Asserts the plaintext
// round-trips; a 404 on reveal after a successful create is a lost secret.
//
//   ./run.sh create-reveal
//   PROFILE=breakpoint RATES=100,200,400,800 ./run.sh create-reveal

import { check } from "k6";
import { Counter } from "k6/metrics";
import { buildOptions, tagStage } from "./lib/config.js";
import { createMessage, messageBody, parseJson, payload, preflight, revealMessage, think } from "./lib/api.js";
import { makeHandleSummary } from "./lib/summary.js";

const SCENARIO = "create-reveal";
const TEXT = payload(parseInt(__ENV.MESSAGE_BYTES || "200", 10));
const BODY = messageBody(TEXT);

const lostReveals = new Counter("lost_reveals");

export const options = buildOptions(SCENARIO, ["create", "reveal"]);
options.thresholds["lost_reveals{phase:measure}"] = ["count==0"];
export const setup = preflight;
export const handleSummary = makeHandleSummary(SCENARIO);

export default function () {
  tagStage();
  const created = createMessage(BODY);
  const c = parseJson(created);
  const createdOk = check(created, {
    "create 201": (r) => r.status === 201,
    "create returns id+key": () => !!(c && c.messageId && c.aesKey),
  });
  if (!createdOk) return;

  const revealed = revealMessage(c.messageId, c.aesKey);
  const r = parseJson(revealed);
  if (revealed.status === 404) lostReveals.add(1);
  check(revealed, {
    "reveal 200": (res) => res.status === 200,
    "reveal plaintext matches": () => !!(r && r.message === TEXT),
  });
  think();
}
