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
