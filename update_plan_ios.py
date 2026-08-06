import re

with open("/Users/presence/Downloads/chess plan.md", "r") as f:
    content = f.read()

# 0.1 Official timeline
content = content.replace(
    "the last\nsafe production submission to Google Play is roughly **Sep 14**, and to the App Store roughly\n**Sep 10** (review latency + one rejection round).",
    "the last\nsafe production submission to Google Play is roughly **Sep 14** (review latency + one rejection round)."
)
content = content.replace(
    "safe production submission to Google Play is roughly **Sep 14**, and to the App Store roughly\n**Sep 10**",
    "safe production submission to Google Play is roughly **Sep 14**"
)

# 0.2 The reordering
content = content.replace("B21/B22/B23", "B21")
content = content.replace("B21 → B22 → B23 (store releases)", "B21 (store release)")
content = re.sub(r"\| \*\*B22/B23\*\* license \+ App Store \| \*\*Conditional\*\* — see §0\.6 \| Only if chasing Ship Kotlin Everywhere \|\n", "", content)

# 0.4 Monetization design
content = content.replace("Provide a Play promo code + an App Store promo code", "Provide a Play promo code")

# 0.5 Category targeting
content = re.sub(r"\| \*\*Ship Kotlin Everywhere\*\* \(JetBrains\) \| \$15k / \$10k / \$5k \| Perfect on paper — KMP/CMP across 5 targets \| \*\*Requires live listings on BOTH App Store and Google Play\.\*\* See §0\.6 \|\n", "", content)
content = content.replace("**Target four. In priority order:**", "**Target three. In priority order:**")

# 0.6 The iOS fork
old_ios_section = r"## 0\.6 The iOS fork — decide by \*\*Aug 20\*\*, not later.*?## 0\.7 Week-by-week"
new_ios_section = "## 0.6 The iOS fork\n\n> **Decision (2026-08-05):** Abandoned. No physical iPhone for testing 3D graphics and thermal constraints makes App Store review too risky. Focus entirely on Android for Shipaton.\n\n## 0.7 Week-by-week"
content = re.sub(old_ios_section, new_ios_section, content, flags=re.DOTALL)

# Week 0
content = re.sub(r"- \[ \] \*\*M\*\* Confirm/create \*\*Apple Developer Program\*\* membership \(\$99/yr\) \*if\* chasing iOS\.\n\s*Verification is slower than Google's\.\n", "", content)
content = content.replace("(and App Store Connect's\n      Paid Apps agreement if iOS)", "")
content = content.replace("(and App Store Connect's Paid Apps agreement if iOS)", "")
content = re.sub(r"- \[ \] \*\*M\*\* Email the 8 contributors for relicensing consent \(§0\.6\)\. Longest lead time in the plan\.\n", "", content)

# Week 3
content = re.sub(r"- \[ \] \*\*M\*\* \*\*Aug 20 — iOS go/no-go\.\*\* Go only if consent replies are landing \*and\* week 1–2 shipped\n\s*on time\. Otherwise commit to Android-only and drop Ship Kotlin Everywhere\. Write the decision\n\s*down here\.\n", "", content)

# Week 5-6
content = re.sub(r"- \[ \] \*\*C\*\* iOS track only if go: engine swap, `MoveAssessment` recalibration, relicense, App Store\n\s*submission targeting \*\*live by Sep 10\*\*\.\n", "", content)

# Week 8
content = content.replace(
    "- *Ship Kotlin Everywhere*: both live store URLs, a description of Kotlin usage, links to\n        blog posts/devlogs, and the published-library contributions (`:chess-core`, `:onDeviceAi`,\n        `:coachApi` on GitHub Packages + the React Native consumer repo — this is a genuine\n        ecosystem-contribution answer most entrants can't give).\n",
    ""
)

# 0.8 Perks
content = re.sub(r"\*\*Claim only if the fork goes that way:\*\*.*?\*\*Skip:\*\*", "**Skip:**", content, flags=re.DOTALL)

# B7. VA-3 — iOS upgrade
content = re.sub(r"## B7\. VA-3 — iOS upgrade.*?## B8\.", "## B7. VA-3 — iOS upgrade\n\n> **Abandoned (2026-08-05).** No iOS App Store release planned.\n\n## B8.", content, flags=re.DOTALL)


with open("/Users/presence/Downloads/chess plan.md", "w") as f:
    f.write(content)

print("iOS references removed!")
