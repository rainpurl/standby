# standby+ — backup, 2026-09-06

## What's here

    superclock/            the git repository — this is what goes to GitHub
    DO-NOT-COMMIT/         signing key and passwords — keep private, never push
    standby-plus-1.4.apk   the signed build currently installed on the phone

## Pushing to GitHub

The repository is ready to push as-is. From `superclock/`:

    git remote add origin git@github.com:rainpurl/standby-plus.git
    git push -u origin main

### What was done to make that possible

Two files made the original repository unpushable, and both are fixed:

- **The 125 MB speech model exceeded GitHub's 100 MB hard per-file limit.** The
  push would have been rejected outright. It is a third-party artifact
  reproducible from a URL, so it is no longer committed —
  `scripts/fetch-model.sh` downloads it, and the build fails with a clear message
  if it is missing.
- **A 59 MB APK had been committed by accident.** Removed.

History was rewritten to purge both from every commit, so they are not merely
deleted going forward — they are absent from the whole history, which is what
GitHub actually checks. All eight commits and their messages are intact.
`.git` went from **220 MB to 4.4 MB**.

## Building it fresh

    cd superclock
    ./scripts/fetch-model.sh            # ~125 MB, one time
    echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
    JAVA_HOME=~/.jdks/jdk-17.0.20.1+1/Contents/Home ./gradlew assembleRelease

Needs JDK 17 and Android SDK platform 35. Without `keystore.properties` you get
`app-release-unsigned.apk`; see `DO-NOT-COMMIT/READ-ME-FIRST.txt` to sign it.

Verified: the stripped repository builds cleanly and all 69 tests pass.

## What is NOT in the repo, deliberately

| | why |
|---|---|
| `app/src/main/assets/model-en-us.zip` | 125 MB, over GitHub's limit; fetched by script |
| `keystore.properties` | signing passwords in plaintext |
| `local.properties` | machine-specific SDK path |
| `*.apk` | build output |

## State as installed on the phone

- Wake word **"alarm"**, doubling as the command cue: *"alarm for seven a m"*
- Speaks a confirmation: *"Alarm set for Thursday, 7 20 AM"*
- Digits at **83% of screen height** (measured on-device, not estimated)
- Landscape-locked, full-screen through the display cutout
- Swipe along the long axis to bias brightness; toward the notch is brighter
- Alarm tone is your `echoes.mp3`

## Still unverified

None of these could be checked from this machine — they need you and the phone:

- whether recognition is actually better with the larger model
- whether the spoken confirmation is audible (needs Google TTS offline voice data)
- whether the phone ever wakes itself on hearing its own confirmation
- whether an alarm genuinely fires overnight with the screen off
