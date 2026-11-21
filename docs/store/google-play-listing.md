# Google Play listing — source of truth

Paste-ready text for the Play Console (`io.github.ber4444.chess`). Keep this file in step with the
console and with the privacy policy on the `gh-pages` branch; the description is what a store
visitor reads, and the Data safety answers are what Google holds the app to.

Drafted 2026-11-13 against `main` at `7d3d2b19`. What changed from the live listing and why:

- The **PRO** and **WHAT'S FREE** sections are gone. There are no purchases, subscriptions or
  entitlements on any platform; every surface renders wherever the build can run it.
- "The only permission the app requests is internet access" was true of `AndroidManifest.xml` but
  not of the installed APK, whose merged manifest also carries `ACCESS_NETWORK_STATE`, `VIBRATE`
  and AICore's `BIND_SERVICE` from libraries. The new wording matches the privacy policy.
- Rules Q&A and the Gemini API are named, because the Data safety form below declares them and the
  description should not say less than the form does.

## Short description (80 chars max)

Unchanged — 73 characters:

```
Play 3D chess against Stockfish and learn why every move you make worked.
```

## Full description (4,000 chars max)

```
Play chess on a real 3D board — and find out why each of your moves worked, or didn't.

AI Chess Coach 3D pairs a full-strength chess engine with coaching that explains your own play, move by move. Free and open source: no account, no ads, no purchases.

THE GAME
• Play as White or Black against Stockfish, the strongest open-source chess engine
• Four difficulty levels, from a gentle Easy to full-strength Max
• Every rule handled properly: castling, en passant, promotion, threefold repetition, the fifty-move rule and insufficient material — and an engine that offers you a draw when the position has genuinely run dry
• A hint whenever you want one
• Games save themselves: close the app mid-game and pick up exactly where you left off
• Full game history, with PGN export you can share or open in any chess program

TWO BOARDS
• A real-time 3D board you can orbit, zoom and play on directly
• A clean 2D board when you would rather just see the position
• Switch between them any time in Settings

THE COACH
After each of your moves, the coach tells you what the engine saw: whether the move was best, good, an inaccuracy or a blunder, how much it cost you, the tactics involved, and which move would have been stronger. It is computed from the engine's own analysis of the position in front of you, so it is about your game and nothing else.

MORE COACHING
• Game summary — the turning points of a finished game, ranked by what each one cost
• Opening explainer — the opening you actually played, identified from your moves and explained from an opening reference
• Position chat — ask questions about the position on the board and get answers grounded in that same reference
• Rules Q&A — ask a chess-rules question in plain language and get an answer cited to the rule it came from. On phones that support Gemini Nano, Google's on-device model phrases the answer; everywhere else the app answers straight from its bundled rulebook

Everything above is free. There is nothing to unlock.

PRIVACY
• No account, no sign-in, no ads, and no analytics or crash-reporting SDKs
• Your games, history and settings stay on your device — uninstalling removes them
• The app never shows a permission prompt. It uses the internet only for the opening explainer and position chat; the other permissions in the installed app come from Android's on-device AI service and the 3D board library
• The opening explainer and position chat send the chess position and your typed question to our service, which composes the answer with Google's Gemini API. Nothing that identifies you is attached
• Rules Q&A on Android uses Google's ML Kit library, which reports diagnostics about its own operation to Google
Full policy: https://ber4444.github.io/compose-multiplatform-chess/

OPEN SOURCE
The complete source code is public at https://github.com/ber4444/compose-multiplatform-chess, so you can read exactly what the app does.

Questions, bugs and feature requests: https://github.com/ber4444/compose-multiplatform-chess/issues
```

## Data safety (App content → Data safety)

The live form says "no data collected or shared". That contradicts the privacy policy on two
points, and Google's own [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
makes the app developer responsible for declaring what the library collects. Answers below; the
two judgment calls are marked.

### Overview questions

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS to Fly.io; ML Kit uses HTTPS) |
| Do you provide a way for users to request that their data is deleted? | **No** — *judgment call.* Nothing the app sends is retained by us, so there is nothing we could delete; ML Kit's identifiers are held by Google. If the form offers the "data is not retained / ephemeral" reason, pick it. |

### Data types to declare

**Messages → Other in-app messages** (the Position Chat text box)

| Field | Answer |
| --- | --- |
| Collected? | Yes |
| Shared? | No — Gemini processes it on our behalf as a service provider, which Play exempts from "sharing" |
| Processed ephemerally? | **No** — *judgment call.* Our server holds it in memory only, but Google logs Gemini prompts for abuse detection for a limited period, which fails Play's "no longer than needed to service the request" test |
| Required or optional? | Optional — only sent when the user types in Chat |
| Purposes | App functionality |

**Device or other IDs** (ML Kit, Rules Q&A on Android)

| Field | Answer |
| --- | --- |
| Collected? | Yes |
| Shared? | No — Google states ML Kit does not transfer it to third parties |
| Processed ephemerally? | No |
| Required or optional? | Optional — only when Rules Q&A is used |
| Purposes | Analytics (Google's wording: "diagnostics and usage analytics") |

**App info and performance → Diagnostics** and **Other app performance data** (ML Kit)

| Field | Answer |
| --- | --- |
| Collected? | Yes |
| Shared? | No |
| Processed ephemerally? | No |
| Required or optional? | Optional |
| Purposes | Analytics |

Google's disclosure lists, for ML Kit GenAI: device make/model/OS, package name and version,
per-installation and "user, device or other" identifiers, latency, API configuration, input and
output *sizes*, feature version, event types, error codes, configured languages. The three rows
above are the Play data types those map onto. Device model and OS version are not Play data
types on their own.

### Not declared, and why

- **Chess position and moves** (FEN, SAN) — not a Play data type; they are not personal data.
- **IP address** — used only for in-memory rate limiting for 60 seconds; Play does not require
  declaring IP handled that way.
- **Games, history, settings** — never leave the device. Android Auto Backup copies them to the
  user's own Google account; that is the OS, not the app, and is not a collection event.
- **Locale** — the policy says the cloud features send "your language preference"; both clients
  actually send `locale = null`. Nothing to declare, and the policy sentence can go.

### Submitting

Edit the default store listing and the Data safety form, then publish them from
**Publishing overview** (managed publishing holds changes there). A description change alone does
not need a new release; the Data safety change takes effect on the listing when approved.
