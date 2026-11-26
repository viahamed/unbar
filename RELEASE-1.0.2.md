# Unbar 1.0.2 — update list (local, not published)

Branch: `release-1.0.2` (from `main` / v1.0.1, 93a6524). Not pushed.

## Done locally
- [x] New app icon, option B: blue "U" + orange striped "b" on white.
  - Adaptive icon: new foreground (inside the safe zone) and white background, day and night.
  - Legacy and round launcher icons, all densities.
  - F-Droid store icon `fastlane/.../images/icon.png` (en-US, fa), round, no tagline.
  - Reason: F-Droid reviewer feedback on MR !58963 (icon looked rough as a square inside a circle).

## To do before release
- [x] Bump version: versionName 1.0.2, versionCode 3 (F-Droid builds 31/32/33 per ABI, VercodeOperation 10*%c+1..3).
- [x] Changelog `fastlane/metadata/android/{en-US,fa}/changelogs/31.txt, 32.txt, 33.txt` (F-Droid versionCodes).
- [x] No build-script changes since v1.0.1 (build.gradle.kts only versionCode/versionName), so the F-Droid recipe's prebuild seds still match. Signing config untouched: same release keystore from GitHub secrets, so the signing certificate SHA-256 should stay the same. To confirm after the build: compare the 1.0.2 APK certificate fingerprint with 1.0.1 (apksigner verify --print-certs).
- [ ] Telegram handle: pick one spelling (`Break_The_Barriers` vs `break_the_barriers` in full_description.txt).
- [ ] Check icon on a real device (light, dark, themed icons).
- [ ] Other changes for this version: (add here)
- [ ] Build, sign, GitHub release v1.0.2 with APKs.
- [x] F-Droid: no recipe MR needed. AutoUpdateMode: Version + UpdateCheckMode: Tags picks up tag v1.0.2.
- [ ] Update site (unbar.cc) icon and version.
- [x] en-US phoneScreenshots replaced with 8 new English screenshots from the web demo (user approved 2026-11-26). F-Droid shows them after the 1.0.2 tag is built. fa screenshots unchanged.

- [x] Icon changed to simple option 6 (2026-11-26): white rounded square, solid blue U + solid orange b (Poppins Black), no stripes. Replaces striped option B. Adaptive fg/bg, legacy + round mipmaps, fastlane icon.png (en-US, fa).
- [x] Brand kit in branding/ (2026-11-26): SVG masters (text as outlines) + PNG exports — wordmark light/dark/white/black, lockup light/dark (512/1024/2048 px), icon square/round/dark/full-bleed/mono (16–1024 px), favicon.ico, adaptive foreground. Colours: blue #0D52A0, dark-bg blue #4F95EC, orange #FF7A13, ink #0A162C. App mipmaps and fastlane icon.png re-exported from the SVG masters.
- [ ] Site: replace assets/wordmark-*.png, assets/lockup-*.png, favicon, apple-touch-icon with the new kit (ready files in /workspace/work/unbar-brand/site/). Needs approval before deploy.
- [ ] README / GitHub social preview: use branding/png/lockup-light-1024.png (optional).

- [x] Cloud theme (2026-11-26, user chose B "ابری"): softer, cleaner light and dark themes.
  - Theme.kt: light bg #F2F6FB, surface #FFFFFF, primary #1A5BAA, orange #F7853A; dark bg #101722, surface #18212E, primary #6E9FDB, orange #F7954A. All surface containers set explicitly (blue-tinted neutrals).
  - Connected status card and floating menu capsule now follow the theme (UnbarHeroBlue, UnbarCapsuleBlue). Small UI code change only (Theme.kt, HomeScreen.kt, HistoryScreen.kt, UnbarApp.kt); no logic or build-script changes.
  - colors.xml / themes.xml (launch window, splash, widget, status/nav bar) mirror the new palette.
  - Logos recoloured to the same blue/orange: in-app Unbar logo + wordmark (light/dark), Break The Barriers logo, stat icons.
  - Brand kit colours: blue #1A5BAA, dark-bg blue #6E9FDB, orange #F7853A, ink #101722. Icons and mipmaps re-exported; fastlane icon.png updated.
  - en-US screenshots re-rendered from the recoloured web demo. Changelogs 31-33 updated.
  - Site (local, not deployed): style.css variables, demo.html, assets/ logos, img/logo.png, img/btb.png, img/favicon.png recoloured.
  - Not compiled locally (no Android SDK in the sandbox): let CI build the pushed branch before tagging.
- [x] In-app logos switched to the brand kit wordmark S1 (2026-11-26, user confirmed the kit is the new logo/icon set): unbar_wordmark_{light,dark}.xml and unbar_logo_{light,dark}.xml (wordmark + "by Break The Barriers" tagline) regenerated from Poppins Black outlines in Cloud colours; same canvas sizes, so UnbarLogo.kt and the launch window are unchanged. Site demo assets, site header logo and screenshots re-rendered to match.

## Resolver import (2026-11-26)
- [x] New "Import" chip right of Custom (Settings > Resolvers). Picks a .txt via the system file picker (ACTION_OPEN_DOCUMENT, no storage permission), max 1 MB, UTF-8/UTF-16.
- [x] Cleaning (data/datastore/ResolverFileImport.kt): comments (# and //), surrounding text, Persian/Arabic digits and separators, missing :53, leading zeros, CIDR host bits -> network address, dash ranges -> exact CIDR blocks, duplicates, singles :53 inside a range, ranges inside a larger range, sorted by address. IPv6/hostnames/invalid reported as ignored. Report shown after import.
- [x] Shared normalizer upgraded (Custom gets the same per-entry fixes: Persian digits, leading zeros, host bits, dash ranges). Custom keeps its own order (no sorting).
- [x] Separate stored list (imported_resolvers, import_file_name, imported_at_ms); DiscoveryMode.IMPORT behaves like CUSTOM in TunnelManager (no discovery; scan/connect use only this list). Scan UI unchanged (same 3-stage stepper).
- [x] Limits unchanged from the core: ranges up to /20; scan cap 5000 addresses (warning shown).
- [x] Changelogs 31-33 (en-US, fa) updated.
- [ ] Not compiled here (no Android SDK). Cleaner logic was compiled and tested with kotlinc 2.0.21 on JVM. Needs CI build + device test (pick file, replace, clear, scan, connect, switch Custom<->Import keeps both lists).

## Capsule menu + text centring (2026-11-26)
- [x] Capsule 316dp wide when open (was 340dp); items 63dp, labels 12sp, icons 22dp.
- [x] Every item has the same box (pill insets on selected and unselected), so icons sit on one line, nothing shifts when the selection moves, and the block has equal space above and below (5dp/5dp in the 56dp capsule; menu button 14dp/14dp).
- [x] App-wide: typography with text centred in its line box (LineHeightStyle Center) so icon+label rows, chips and pills stay vertically centred.
- [x] Web demo capsule updated to match; en-US F-Droid screenshots re-rendered.
- [x] Nested-radius rule app-wide (outer = inner + padding): top-level cards (Settings, About, Tutorial, Servers empty state, Add dialog) 24dp with 16dp padding; panels, list boxes, text fields, menus and Add cards inside them 8dp (Theme shapes extraSmall/small = 8dp); capsule pill 11dp (16dp capsule, 5dp inset); mode switch 10dp track, 2dp inset, 8dp segment (already correct). Small floating pills/badges that do not hug a container edge keep their shape. Web demo matched; screenshots re-rendered.
