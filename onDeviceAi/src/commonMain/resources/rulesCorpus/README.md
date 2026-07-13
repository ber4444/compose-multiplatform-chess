# Rules corpus

These are concise project-authored adaptations of the Wikibooks **Chess/Rules** material. The
source material is CC BY-SA 4.0/GFDL; this adapted corpus is distributed under CC BY-SA 4.0.
See the root `THIRD_PARTY_NOTICES.md` for attribution and license links.

`passages.tsv` is the source of truth. The `:onDeviceAi:generateRulesCorpus` build task validates
the rows and embeds them into common Kotlin so Android, iOS, desktop, and Wasm use identical
offline content without a runtime database.
