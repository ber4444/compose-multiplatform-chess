# Solo cloud saves pilot

Use a dedicated Firebase project with Firestore Standard in Native mode and Email/Password sign-in enabled. Configure verification and password-reset emails. From this directory, apply the Firestore rules and index to the selected staging project.

```bash
firebase deploy --only firestore:rules,firestore:indexes --project STAGING_PROJECT_ID
```

The Firestore rules deny client access. The Ktor server uses its own service account for Firestore data and Firebase Auth account checks and deletion. Give the service account access only to the saves project. Store its JSON in the Fly secret `SAVES_FIREBASE_SERVICE_ACCOUNT_JSON`. Set `SAVES_FIREBASE_PROJECT_ID` to the same Firebase project ID. Add the Wasm site's hostname to the existing `COACH_ALLOWED_ORIGINS` list.

The server mounts its save routes when either `SAVES_HISTORY_ENABLED=1` or `SAVES_RESUME_ENABLED=1` is set. Keep both disabled until the rules and index are active and staging has passed the route and account isolation checks. The Firebase Admin setup rejects the emulator environment variables in the production entry point.

Build the app with the Firebase **Web API key**, a public client identifier, and the HTTPS Ktor origin. Enable history and resume separately.

```bash
CHESS_FIREBASE_WEB_API_KEY=FIREBASE_WEB_API_KEY \
CHESS_SAVES_BASE_URL=https://STAGING_FLY_HOST \
CHESS_ENABLE_SOLO_HISTORY_SYNC=1 \
./gradlew :app:run
```

Add `CHESS_ENABLE_SOLO_RESUME_SYNC=1` to build the resume pilot. The Lichess pilot is independent and uses `CHESS_ENABLE_MULTIPLAYER=1`. Keep both auth sessions in memory. After relaunch, sign into the same account to access its queued cloud changes. Local play and local PGN copies remain available. Confirm which game to keep when connecting an existing device snapshot. A solo game already linked to one account stays local when another account signs in. Finish or reset that local game before resuming under the other account.

Run the Firestore repository integration test against a local emulator from the repository root.

```bash
npx --yes firebase-tools emulators:exec --only firestore --project demo-chess-saves \
  --config server/firebase/firebase.json \
  './gradlew :server:test --tests "*FirestoreCloudSaveRepositoryTest" --tests "*CloudSaveRoutesTest"'
```

Use two test accounts and two devices to verify isolation, lost acknowledgements, an offline reset, deletion, and recovery from conflicting solo positions. Verify the app can resume a Lichess match and load its archive with the Fly save routes disabled. Review Fly memory and latency before enabling the pilot against the 512 MB coach deployment. Disable the server and app switches to roll back. Retain Firestore and local journals for retry.

See the [API contract](../saves.openapi.yaml) and the [implementation and release gates](../../docs/superpowers/plans/2026-11-06-lichess-multiplayer.md).
