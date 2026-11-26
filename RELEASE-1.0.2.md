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

- [x] Nested-radius rule extended to every remaining screen (2026-11-26, user asked for the whole app, site, demo and screenshots): Home stat cards, no-server card, History, Logs (padding 10 -> 14), server cards, subscription card and rows now 24dp; connected status card 30dp (10dp mode switch sits 20dp from its corner); dialogs 32dp (24dp inset + 8dp inner); QR box 8dp. Icon/text rows on server cards and the subscription paste row explicitly centred.
- [x] Site (local): style.css radius system (cards 24px, small boxes 16px, controls inside 8px; code copy button and wallet-address copy button inset to match). Demo: status card 30px, stats/servers/notice/about/scan cards 24px, dialog 32px with 24px padding, QR 8px. en-US screenshots re-rendered.

## Resolver import (2026-11-26)
- [x] New "Import" chip right of Custom (Settings > Resolvers). Picks a .txt via the system file picker (ACTION_OPEN_DOCUMENT, no storage permission), max 1 MB, UTF-8/UTF-16.
- [x] Cleaning (data/datastore/ResolverFileImport.kt): comments (# and //), surrounding text, Persian/Arabic digits and separators, missing :53, leading zeros, CIDR host bits -> network address, dash ranges -> exact CIDR blocks, duplicates, singles :53 inside a range, ranges inside a larger range, sorted by address. IPv6/hostnames/invalid reported as ignored. Report shown after import.
- [x] Shared normalizer upgraded (Custom gets the same per-entry fixes: Persian digits, leading zeros, host bits, dash ranges). Custom keeps its own order (no sorting).
- [x] Separate stored list (imported_resolvers, import_file_name, imported_at_ms); DiscoveryMode.IMPORT behaves like CUSTOM in TunnelManager (no discovery; scan/connect use only this list). Scan UI unchanged (same 3-stage stepper).
- [x] Limits unchanged from the core: ranges up to /20; scan cap 5000 addresses (warning shown).
- [x] Changelogs 31-33 (en-US, fa) updated.
- [x] Web demo: Import chip + file card (sample resolvers-iran.txt), fix report, Replace/View list/Clear. en-US screenshot 4 now shows Import mode with the scan stepper.
- [ ] Not compiled here (no Android SDK). Cleaner logic was compiled and tested with kotlinc 2.0.21 on JVM. Needs CI build + device test (pick file, replace, clear, scan, connect, switch Custom<->Import keeps both lists).

## Capsule menu + text centring (2026-11-26)
- [x] Capsule 316dp wide when open (was 340dp); items row 260dp, labels 12sp, icons 22dp.
- [x] Every item has the same box (pill insets on selected and unselected), so icons sit on one line, nothing shifts when the selection moves, and the block has equal space above and below (5dp/5dp in the 56dp capsule; menu button 14dp/14dp).
- [x] App-wide: typography with text centred in its line box (LineHeightStyle Center) so icon+label rows, chips and pills stay vertically centred.
- [x] Web demo capsule updated to match; en-US F-Droid screenshots re-rendered.
- [x] Nested-radius rule app-wide (outer = inner + padding): top-level cards (Settings, About, Tutorial, Servers empty state, Add dialog) 24dp with 16dp padding; panels, list boxes, text fields, menus and Add cards inside them 8dp (Theme shapes extraSmall/small = 8dp); capsule pill 11dp (16dp capsule, 5dp inset); mode switch 10dp track, 2dp inset, 8dp segment (already correct). Small floating pills/badges that do not hug a container edge keep their shape. Web demo matched; screenshots re-rendered.

## On push/build (user rule, 2026-11-26 15:27)
- [ ] When the user says push and build: also update the GitHub repo to match 1.0.2 — README (features, incl. resolver Import in the Resolvers section; version 1.0.2; Android + iOS notes), repo description/topics if needed.
- [ ] GitHub release v1.0.2 body: full list of what changed in this version (Cloud theme, new logo/icon, resolver Import, capsule menu, nested-radius/centring UX pass, iOS parity). Show the exact text to the user before publishing.

## iOS (2026-11-26)
- [x] Version 1.0.2 (build 3) for both targets: `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` build settings (set in `ios/tools/generate_project.py`, pbxproj regenerated); both Info.plists now read `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)` (were hard-coded 1.0.0 / 1).
- [x] Cloud palette in the asset catalog (light + dark appearances): AppBackground, SplashBackground, Surface, SurfaceVariant, BrandBlue (primary), PrimaryContainer, BrandOrange, AccentColor, SecondaryContainer, Ink, Muted, Outline, HeroBlue (connected status card), CapsuleBlue (menu capsule). Hard-coded capsule #10367C and system grey card backgrounds replaced; Form/List screens use AppBackground/Surface via UITableView appearance (iOS 14/15).
- [x] Logos: in-app Logo = Android's unbar_logo_{light,dark} (S1 wordmark + tagline), rendered to transparent PNG (1730x673); splash, About and LaunchScreen resized for the wide logo; launch screen background = SplashBackground. ChannelLogo, MagnifierCheck and ShieldCheck SVGs recoloured (#0B4F9E -> #1A5BAA, #FF7A1A -> #F7853A). Stat icons follow BrandBlue/BrandOrange colorsets.
- [x] AppIcon: all sizes re-exported from branding/png/icon-1024.png (flattened on white) and icon-dark-1024.png (flattened on #101722), opaque RGB, no alpha.
- [x] Resolver Import: new "Import" segment right after Custom (fa "وارد کردن"). `.fileImporter` ([.plainText], security-scoped), max 1 MB, UTF-8 / UTF-8 BOM / UTF-16 BOM. Cleaner ported from ResolverFileImport.kt (`ResolverFileImport.swift`), normalizer upgraded like Android (Persian/Arabic digits and separators, zero-width removal, dash ranges -> CIDR, leading zeros, host bits, ASCII-only digits). Separate stored list (importedResolvers, importFileName, importedAtMs) in the settings snapshot; Import behaves like Custom (no discovery, connect/scan/probe use only that list); Custom's list untouched; config export shares an imported list as Custom. UI (`ResolverImportView.swift`): file card, Replace, View list (first 200), Clear, fix report, over-cap and oversize-range warnings. Scan row/stepper unchanged. en + fa strings.
- [x] Capsule menu: 316 open, 56 tall, radius 16; items 63 wide, same box for every item (icon 22, gap 2, 12pt label in a 14pt line, 5/4 insets), orange pill radius 11 on the selected one; menu button 56x56.
- [x] Nested-radius rule: `UnbarRadius` (outer 24, inner 8, hero 24, capsule 16, pill 11, card padding 16) in Components.swift; Home stat cards, quality/scan cards, server row, status card (24: iOS has no mode switch on it), server cards, subscription card/rows, Add button, About rows/donation card, tutorial card (Home/Servers) at 24 with 16 padding; import file card/list box at 8; chips and small pills are capsules.
- [x] Explicit centre alignment in icon+label rows; MTU / answer-size chips in a fixed 34pt box.
- [ ] Not compiled (no Xcode here); needs an Xcode build and device test.
