<div align="center">

<img src="assets/gapwise-android.svg" width="116" alt="Gapwise for Android logo" />

# Gapwise for Android

### The native Android client for Gapwise.

**A privacy-first Kotlin + Jetpack Compose app for University of Toronto timetables, with secure on-device persistence, optional encrypted account sync, and a UTM-focused native map.**

[![Android](https://img.shields.io/badge/Android-Native-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Native_UI-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![MIT](https://img.shields.io/badge/License-MIT-111111?style=for-the-badge)](LICENSE)

<sub>Kotlin · Jetpack Compose · Material 3 · Android Keystore · MapLibre · Supabase</sub>

<br />

**[Gapwise](https://gapwise.ca)** · **[Android](https://github.com/Gapwise-for-UofT/android)** · **[iOS](https://github.com/Gapwise-for-UofT/ios)** · **[AI](https://ai.gapwise.ca)** · **[Data](https://data.gapwise.ca)** · **[Docs](https://docs.gapwise.ca)** · **[Status](https://status.gapwise.ca)**

</div>

---

## What Gapwise for Android is

Gapwise for Android is the native Android client for **[Gapwise](https://gapwise.ca)**, a timetable-intelligence platform for University of Toronto students.

The timetable layer supports **UTM, UTSG, UTSC, and mixed-campus schedules**. Campus identity and the original ACORN location string are preserved instead of being collapsed into one campus namespace. The first-party native map remains **UTM-focused** until equally grounded campus data exists elsewhere.

This is a real native Android application rather than a WebView wrapper. Android owns navigation, storage, lifecycle behavior, platform authentication hand-off, theming, and map rendering while the wider Gapwise ecosystem remains the source of truth for shared product semantics.

---

## Current implementation

The current app includes:

- local ACORN `.ics` import through the Android system document picker;
- UTM, UTSG, UTSC, and mixed-campus timetable parsing;
- normalized timetable persistence in app-private encrypted storage;
- **Today**, **Timetable**, **Gap Plan**, **Map**, and **More** navigation aligned with the mobile Gapwise information architecture;
- current-term/day-aware Today behavior and timetable term/weekday views;
- reserved-assessment (`RES`) handling for source-backed assessment windows;
- light and dark appearance modes;
- a native UTM MapLibre/OpenFreeMap map with building search, imported-class destinations, pan/zoom, compass, and theme-aware styles;
- optional Gapwise account sign-in with Google, Microsoft, or GitHub through Supabase Auth PKCE;
- optional encrypted account sync for normalized private timetable state;
- account/cloud-data deletion controls;
- native settings for appearance, routing preferences, campus-arrival context, Gap Plan preferences, account/sync, timetable management, privacy, integrations, exports, and academic-work entry points.

The Android map is functional but **does not yet claim full feature parity with the web UTM routing/map stack**. In particular, the web app's complete source-backed building geometry, entrance graph, indoor context, and route engine remain a separate implementation milestone for native Android.

---

## Privacy and security

The timetable flow is deliberately local-first:

- the original ACORN `.ics` source is read on-device and is not retained as the app's persisted timetable;
- only normalized meeting state is stored locally;
- local timetable/session state is encrypted with **AES-256-GCM**;
- the encryption key is non-exportable and held by **Android Keystore**;
- OAuth session tokens are stored through the same Keystore-backed encrypted local store;
- cloud sync is optional and only available after explicit sign-in and opt-in;
- the native sync client implements Gapwise's encrypted private-cloud protocol rather than uploading raw calendar bytes;
- `android:allowBackup="false"` is used for the app;
- the app requests Internet access for map tiles, authentication, and optional sync—not for basic local timetable parsing.

Gapwise does not describe its private-cloud architecture as zero-knowledge or end-to-end encrypted where the wider trust boundary does not actually support those claims.

---

## Native map

The Android map uses **MapLibre Native** with OpenFreeMap light/dark styles and is centered on UTM.

Current behavior includes building search, campus recentering, mapped-building markers, imported UTM class context, marker selection, pan/zoom gestures, and compass controls. The MapLibre logo is disabled while required map attribution remains enabled.

UTSG and UTSC rooms remain valid timetable locations but are never falsely plotted onto the UTM map.

---

## Account continuity

A Gapwise account is optional. Core timetable use remains available without sign-in.

When signed in, Android can use the same first-party account system as the web app:

- Google, Microsoft, or GitHub OAuth through Supabase;
- PKCE with the `gapwise://auth-callback` app callback;
- encrypted local session storage;
- explicit opt-in encrypted sync;
- manual sync/load controls;
- deletion of encrypted cloud data without deleting the device timetable;
- permanent account deletion with an optional local-timetable clear.

The Supabase project must allow this redirect URI:

```text
gapwise://auth-callback
```

---

## Architecture

The native codebase keeps product responsibilities separated:

```text
app/src/main/java/ca/gapwise/android/
├── core/
│   ├── designsystem/
│   ├── model/
│   └── persistence/
├── data/
│   ├── account/
│   ├── sync/
│   └── timetable/
├── feature/
│   ├── gapplan/
│   ├── map/
│   ├── settings/
│   ├── timetable/
│   └── today/
└── navigation/
```

Key implementation boundaries include timetable parsing, Keystore-backed persistence, account/auth, encrypted sync, map integration, app preferences, navigation, and feature UI.

---

## Local development

Open the repository root in Android Studio and use **JDK 17**.

```bash
git clone https://github.com/Gapwise-for-UofT/android.git
cd android
```

The project targets modern Android SDKs and should be tested on both emulators and physical hardware before release. Emulator performance—especially MapLibre rendering—should not be treated as a substitute for real-device performance testing.

---

## Gapwise ecosystem

| Repository | Role | Primary surface |
| --- | --- | --- |
| **[`gapwise`](https://github.com/Gapwise-for-UofT/gapwise)** | Core web/PWA, canonical timetable/gap/routing semantics, public API, OpenAPI, and SDK source | [gapwise.ca](https://gapwise.ca) / [api.gapwise.ca](https://api.gapwise.ca/v1) |
| **[`android`](https://github.com/Gapwise-for-UofT/android)** | Native Kotlin + Jetpack Compose Android client | Android app |
| **[`ios`](https://github.com/Gapwise-for-UofT/ios)** | Native Swift + SwiftUI iOS client | iOS app |
| **[`ai`](https://github.com/Gapwise-for-UofT/ai)** | OAuth/MCP layer for public University of Toronto campus intelligence and explicitly delegated student context | [ai.gapwise.ca](https://ai.gapwise.ca) |
| **[`data`](https://github.com/Gapwise-for-UofT/data)** | Canonical public University of Toronto campus data, provenance, schemas, validation, and distribution | [data.gapwise.ca](https://data.gapwise.ca) |
| **[`docs`](https://github.com/Gapwise-for-UofT/docs)** | Canonical public developer documentation | [docs.gapwise.ca](https://docs.gapwise.ca) |
| **[`status`](https://github.com/Gapwise-for-UofT/status)** | Independent service-health monitoring and incident communication | [status.gapwise.ca](https://status.gapwise.ca) |

All seven repositories are separate implementation and trust boundaries within one Gapwise product ecosystem. Organization-wide GitHub defaults live in [`.github`](https://github.com/Gapwise-for-UofT/.github).

---

## Independent project

> **Gapwise is an independent student software project created by Andrew Muratov. It is not affiliated with, endorsed by, or an official service of the University of Toronto.**

## License

Original project code and documentation are available under the [MIT License](LICENSE).

<div align="center">

**Built for the spaces between classes — native on Android.**

[Open Gapwise →](https://gapwise.ca)

</div>

### Local validation

Use Java 17 or newer, the Android SDK configured for this project, and Gradle 9.6.0
(the version in `gradle/wrapper/gradle-wrapper.properties` and CI):

```bash
gradle :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```

The repository currently contains wrapper properties but no wrapper executable/JAR, so
install that Gradle version explicitly. The optional encrypted-sync adapter must send the
key broker's first-party `Origin` as well as the user's bearer token; the broker's web origin
guard remains enforced. Device/Keystore/OAuth behavior still needs emulator or device validation.
