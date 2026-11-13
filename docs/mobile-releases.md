# Weekly mobile test builds

[Codemagic](https://codemagic.io/app/6af6989aba82258cb4bd437a/settings) runs the two workflows in
[`codemagic.yaml`](../codemagic.yaml). They use the same schedule and tester destinations as
[`kmp-videos`](https://github.com/ber4444/kmp-videos/blob/main/codemagic.yaml):

| Workflow | Destination | Branch and schedule |
| --- | --- | --- |
| `android-release` | Google Play **Internal testing** | `main`, Monday at 06:00 UTC |
| `ios-release` | TestFlight **Internal Testing** | `main`, Monday at 06:00 UTC |

Both use `io.github.ber4444.chess`. The workflows take the highest build number reported by the
store and add one. Bump the public version separately in `androidApp/build.gradle.kts`
(`versionName`) and `iosApp/project.yml` (`MARKETING_VERSION`; regenerate with XcodeGen).

## One-time setup

1. In **Play Console**, verify the chess app and its Internal testing track. If the app has never
   had an upload, [upload its first signed AAB manually](https://docs.codemagic.io/yaml-publishing/google-play/#preparing-your-first-release).
   For that first upload, build with `./gradlew :androidApp:bundleRelease` using the real upload key
   via `~/key.properties`. Play must already accept that key before you schedule releases.
2. The Android workflow uses the existing `ics` keystore from Codemagic **Settings → Code signing
   identities → Android keystores**. The chess workflow also needs access to the existing Play
   service account JSON as `GOOGLE_PLAY_SERVICE_ACCOUNT_CREDENTIALS` in the `google_play` group.
   If the secret is scoped to kmp-videos, add it to the chess app's **Environment variables** as
   well. Verify the service account can release to testing tracks for chess in Play Console.
3. In **App Store Connect**, verify the chess app record and an internal TestFlight group named
   `Internal Testing`. The workflow uses the existing `Apollo Videos` Apple integration; the key
   must also have access to chess. In Codemagic **Code signing identities**, make an Apple
   Distribution certificate and its matching App Store provisioning profile for the chess bundle
   ID available. If the chess app is on a different Apple team, select that team's integration in
   `codemagic.yaml` instead.
4. Once the workflow file is on `main`, use **Check for configuration files** and run each workflow
   once manually. Confirm that Play shows the new build on Internal testing and TestFlight shows
   the processed build in Internal Testing. Resolve Apple's export compliance prompt if one appears.
5. In **Scheduled builds → Add new schedule**, add one schedule for each workflow using the table
   above. [Codemagic stores schedules in the dashboard](https://docs.codemagic.io/yaml-running-builds/scheduling/).
