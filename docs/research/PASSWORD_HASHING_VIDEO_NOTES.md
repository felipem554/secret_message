# Source notes — "Password Hashing, Salting e Peppering" (Augusto Galego)

Extracted and translated from `~/data/transcription/Password Hashing, Salting e Peppering - Augusto Galego.txt` (Portuguese transcript). This is a raw distillation of the video's technical content, kept as source material for `docs/PASSWORD_HASHING_SPEC.md`. Sponsor content and course/channel promo segments (transcript lines 1–5, 945–967) are omitted as not useful.

## Framing

Introductory (non-expert) explainer on password storage, aimed at preventing "monumental mistakes." Covers hashing, salting, peppering, and how they're normally combined.

## Two threat models

- **Online attack**: attacker repeatedly submits guesses through the live login endpoint until one succeeds. Hashing/salting/peppering do **not** prevent this on their own — a correct guess still logs in.
  - Mitigations: WAF, rate limiting (by device/IP/user), account lockout after N failed attempts, MFA (authenticator code, email/SMS one-time code). A combination of these covers ~99.99% of cases.
- **Offline attack**: attacker obtains a dump of the credentials table directly (breach/leak). This is the harder case and the one hashing/salting/peppering is designed to defeat.
  - If passwords are stored in plaintext, a leak is an immediate total compromise for that app and, given password reuse, likely for other services the user has accounts on.

## Hashing

- Never store passwords in plaintext — no exceptions.
- A hash function is one-way: same input + same parameters → same output, but the output cannot be reversed to recover the input.
- Store the password **hash**, not the password.
- Even with only the hash, an attacker can attempt brute force: try common passwords → dictionary attack → exhaustive combinatorial search, hashing each candidate and comparing.
- Search space grows with charset and length (26 lowercase → 52 with uppercase → 62 with digits → much larger with special characters), which is why sites require mixed-character passwords — it enlarges the space the attacker must search.
- Modern computers are extremely fast (billions/trillions of ops/sec), so charset size alone isn't sufficient defense.
- Countermeasure: deliberately use a hashing function that is slow and memory-hard (unlike a data-structure hash map, where speed is desirable). This makes brute force computationally/economically infeasible — with a sufficiently long password (16–24 chars, mixed charset) and an expensive hash function, brute-forcing at current compute power is estimated in the billions of years.
- Quantum computers are a future concern, but hash algorithms exist that are not broken by quantum computation.
- **Recommended algorithm family: Argon2, specifically Argon2id.**

## Salting

- Problem with hashing alone: identical passwords → identical hashes (same function, same parameters). This enables:
  - **Precomputed hash tables (rainbow-table-style) attacks** — attacker maintains a hash→password mapping computed in advance.
  - **Cross-user/cross-app inference** — if two users share a hash, the attacker knows they share a password; a weaker breached service can reveal a password reused on a stronger one.
- Fix: prepend/mix in a random value (the **salt**) before hashing, unique per user (stored alongside the hash in the same table/row).
- Same password + different salt → different hash. Precomputed tables become useless since nothing was precomputed for that specific salt.
- Salt is not secret — storing it next to the hash is fine and expected. It still defeats precomputation and cross-user hash comparison, because the attacker must redo the (expensive) computation per salt.
- Practical guidance: don't hand-roll salt generation (e.g. incremental IDs). Use the same library that performs the hashing to generate the salt. Salt must be random, sufficiently large, and unique per user/password.
- If two users end up with the same salt, cross-user password-equality inference becomes possible again — uniqueness matters.

## Peppering

- Even with hash + salt, if the attacker also knows the algorithm and its parameters, in theory (e.g. with dramatically more compute) the hash could still be attacked, especially for weaker/shorter passwords.
- **Pepper**: a second, secret, application-wide value that is *not* stored in the database — so a database leak alone does not expose it.
- Typically one pepper value per application, stored in a dedicated secret store separate from the database — e.g. AWS Secrets Manager, Google Secret Manager.
- Combining hash + salt + pepper correctly is described as making an offline attack effectively impossible with current technology, given a reasonably long password.
- **Trade-off / risk**: pepper is riskier operationally than salt. Salt+hash living in the same table make it hard to lose accidentally. If the pepper value is lost, misconfigured, or rotated incorrectly (bad deploy, secret manager mistake), *every* stored password hash becomes unverifiable — 100% of users are locked out and must reset their password. This is why pepper is considered optional/controversial.

## Practical recommendations from the video

1. **Use well-established libraries** for hashing/salting rather than inventing your own scheme — follow Argon2's documentation and standard practice, don't deviate from it.
2. **Or delegate to a higher-level auth framework** (e.g. "Better Auth"-style libraries) that handles hashing/salting/storage correctly, since most developers aren't security specialists. Reasonable default especially for a solo founder / small team.
3. **Or fully outsource to an Identity Provider** (Clerk, Auth0, AWS Cognito, etc.) — maximal delegation; the provider owns the credential-storage problem entirely.
4. **Or avoid passwords altogether**: magic-link email login, or OAuth via Google/GitHub/etc. The speaker's personal preference for consumer (B2C) SaaS, to sidestep the whole problem.

## Closing point

Many real-world apps still get this wrong (plaintext passwords, or weak/outdated hashing algorithms). Worth auditing whatever app/company you work on against this checklist. Video is explicitly introductory — it explains *why* these mechanisms exist and what mistakes to avoid, not a full implementation tutorial.
