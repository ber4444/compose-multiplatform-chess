# App Store Connect — source of truth

Paste-ready text for App Store Connect app 6834273668 (`io.github.ber4444.chess.ios`). Drafted
2026-11-13 against `main` at `7d3d2b19`. The record today has version 1.0 in *Prepare for
Submission* with no description, keywords, support URL, screenshots, category, age rating, content
rights, privacy policy URL or privacy labels, and TestFlight has no builds and empty test
information. The weekly Codemagic workflow only uploads a build; everything below is filled in by
hand, once.

Differences from the [Play listing](google-play-listing.md), all because the iOS build is a
different app: there is no ML Kit, Game Summary and Rules Q&A use Apple's on-device model where
Apple Intelligence is available, and the hint/coach/engine are otherwise identical.

## App Information

| Field | Value |
| --- | --- |
| Name (30) | `AI Chess Coach 3D` (unchanged) |
| Subtitle (30) | `Play Stockfish. Learn why.` |
| Primary category | Games → Board |
| Secondary category | Education |
| Content rights | *Yes, it contains third-party content and I have the rights.* The opening reference is Lichess's CC0 `chess-openings`, the rules corpus is adapted from Wikibooks (CC BY-SA), Stockfish is GPL; all are attributed in Settings → Open-source licences. |
| License agreement | Apple's standard EULA (already set) |
| Pricing and Availability | Free, all territories |

## Age rating questionnaire

Answer **None / No** to every item. The two that look tempting are not:

- *Messaging and Chat* — Apple means user-to-user communication. Position Chat talks to our
  service, never to another person.
- *User-Generated Content* — nothing a user types is shown to any other user.
- *Unrestricted Web Access* — the app opens no web view.

Expected result: 4+ everywhere.

## Version 1.0 page

**Promotional text** (170) — can be changed without a new build:

```
Free and open source. Play Stockfish on a 3D board and get a plain-language explanation of every move you make — computed from the engine's own analysis.
```

**Description** (4,000):

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
• Game summary — the turning points of a finished game, ranked by what each one cost, written on your iPhone by Apple Intelligence (iOS 26 and a supported device)
• Opening explainer — the opening you actually played, identified from your moves and explained from an opening reference
• Position chat — ask questions about the position on the board and get answers grounded in that same reference
• Rules Q&A — ask a chess-rules question in plain language and get an answer cited to the rule it came from. With Apple Intelligence on, the on-device model phrases the answer; otherwise the app answers straight from its bundled rulebook

Everything above is free. There is nothing to unlock.

PRIVACY
• No account, no sign-in, no ads, and no analytics or crash-reporting SDKs
• Your games, history and settings stay on your device — deleting the app removes them
• The app asks for no permissions and never shows a permission prompt
• The opening explainer and position chat send the chess position and your typed question to our service, which composes the answer with Google's Gemini API. Nothing that identifies you is attached
• Game summary and rules Q&A use only the model that runs on your device, never Apple's Private Cloud Compute
Full policy: https://ber4444.github.io/compose-multiplatform-chess/

OPEN SOURCE
The complete source code is public at https://github.com/ber4444/compose-multiplatform-chess, so you can read exactly what the app does.

Questions, bugs and feature requests: https://github.com/ber4444/compose-multiplatform-chess/issues
```

**Keywords** (100, comma-separated, no spaces after commas, don't repeat words from the name):

```
stockfish,board,trainer,openings,rules,engine,learn,analysis,blunder,tactics,puzzle,study
```

| Field | Value |
| --- | --- |
| Support URL | `https://github.com/ber4444/compose-multiplatform-chess/issues` |
| Marketing URL | `https://github.com/ber4444/compose-multiplatform-chess` |
| Copyright | `2026 Gabor Berenyi` |
| Version | `1.0` (must equal `MARKETING_VERSION` in `iosApp/project.yml`) |
| Game Center | off |
| App Store Version Release | Manually release this version |

**App Review Information**

| Field | Value |
| --- | --- |
| Sign-in required | **No** — the app has no accounts (the checkbox currently reads as set; clear it) |
| Contact | your name, phone and email |
| Notes | see below |

```
No sign-in, purchases or accounts. Stockfish is bundled; the game works fully offline.

Two features use our own server (https://compose-chess-opening-coach.fly.dev): the Opening Explainer, shown after a game ends, and the Chat screen. Both send only the board position and the typed question. If the server is unreachable they show a deterministic offline message instead of an error.

Game Summary (button in the game-over dialog) and the model-phrased Rules Q&A need iOS 26 with Apple Intelligence enabled; on other devices Game Summary is not shown and Rules Q&A answers from its bundled rulebook. The Move Coach panel under the board needs no model and works on every device.

Source code: https://github.com/ber4444/compose-multiplatform-chess (GPL-3.0).
```

**Export compliance.** The app uses only HTTPS through the system's TLS, which is exempt. Add
`ITSAppUsesNonExemptEncryption: false` to the `info.properties` block in `iosApp/project.yml`
(and regenerate) so neither App Review nor every weekly TestFlight upload asks the question.
Until it is in the plist, answer the TestFlight prompt once with *No* for the standard-encryption
exemption.

## App Privacy

| Field | Value |
| --- | --- |
| Privacy Policy URL | `https://ber4444.github.io/compose-multiplatform-chess/` |
| User Privacy Choices URL | leave empty |

**Data collection questionnaire.** The iOS app includes no ML Kit, so the only thing that leaves
the device is what the two cloud features send. Apple's four-part test for *optional* disclosure
(not used for tracking, app-functionality only, infrequent and optional, typed by the user with no
name attached) is arguably met, but the privacy policy already discloses it, so declare it and
keep the two documents saying the same thing:

| Question | Answer |
| --- | --- |
| Do you or your third-party partners collect data from this app? | **Yes** |

Data types, both under **User Content**:

| Data type | Linked to the user? | Used for tracking? | Purposes |
| --- | --- | --- | --- |
| Other User Content (the Chat question) | No | No | App Functionality |
| Gameplay Content (board position and moves sent with each request) | No | No | App Functionality |

Resulting label: **Data Not Linked to You — User Content**. Do not declare Diagnostics (no crash
SDK; TestFlight's crash reports are Apple's), Identifiers (none sent), or Location (IP is used for
a 60-second in-memory rate limit, which Apple does not require declaring).

## TestFlight → Test Information

| Field | Value |
| --- | --- |
| Beta App Description | see below |
| Feedback Email | your email |
| Marketing URL | `https://github.com/ber4444/compose-multiplatform-chess` |
| Privacy Policy URL | `https://ber4444.github.io/compose-multiplatform-chess/` |
| Beta App Review contact | your name, phone, email |
| Sign-in required | No |
| Review Notes | same text as the App Review notes above |

```
Weekly test build of AI Chess Coach 3D, built automatically from the main branch every Monday.

Please try: a full game against Stockfish on the 3D board (orbit, zoom, tap to move), the coach panel after your own moves, Hint, the game-over dialog (Save game, Share PGN, Get Coach Summary on iOS 26 with Apple Intelligence), the Opening Explainer, the Chat and Rules screens, and switching to the 2D board in Settings.

What to report: wrong or missing pieces on the 3D board, a tap that moves the wrong piece, a coach line that contradicts the board, any crash, and anything that hangs with no message. Use TestFlight's Send Feedback with a screenshot where you can.

Only the board position and your typed question ever leave the device, and only on the Opening Explainer and Chat screens. Privacy policy: https://ber4444.github.io/compose-multiplatform-chess/
```

The TestFlight group `Internal Testing` exists and the workflow targets it by name; add testers
there. Internal groups need no Beta App Review, so the first weekly build is visible to them as
soon as it finishes processing.

## When the app goes to the App Store

Two places still say TestFlight: the privacy policy's platform line ("iOS, via TestFlight beta")
and its §05 beta section. Update the policy on `gh-pages` the same day the version is released,
and set `submit_to_app_store` in `codemagic.yaml` deliberately rather than by default — a weekly
build should keep landing in TestFlight, not in review.
