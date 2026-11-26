# Unbar 1.0.2 — update list (local, not published)

Branch: `release-1.0.2` (from `main` / v1.0.1, 93a6524). Not pushed.

## Done locally
- [x] New app icon, option B: blue "U" + orange striped "b" on white.
  - Adaptive icon: new foreground (inside the safe zone) and white background, day and night.
  - Legacy and round launcher icons, all densities.
  - F-Droid store icon `fastlane/.../images/icon.png` (en-US, fa), round, no tagline.
  - Reason: F-Droid reviewer feedback on MR !58963 (icon looked rough as a square inside a circle).

## To do before release
- [ ] Bump version: versionName 1.0.2, versionCode 3 (F-Droid builds 31/32/33 per ABI).
- [ ] Changelog `fastlane/metadata/android/{en-US,fa}/changelogs/3.txt`.
- [ ] Telegram handle: pick one spelling (`Break_The_Barriers` vs `break_the_barriers` in full_description.txt).
- [ ] Check icon on a real device (light, dark, themed icons).
- [ ] Other changes for this version: (add here)
- [ ] Build, sign, GitHub release v1.0.2 with APKs.
- [ ] Update F-Droid recipe (new Builds entries), or let auto-update pick it up.
- [ ] Update site (unbar.cc) icon and version.
- [x] en-US phoneScreenshots replaced with 8 new English screenshots from the web demo (user approved 2026-11-26). F-Droid shows them after the 1.0.2 tag is built. fa screenshots unchanged.

- [x] Icon changed to simple option 6 (2026-11-26): white rounded square, solid blue U + solid orange b (Poppins Black), no stripes. Replaces striped option B. Adaptive fg/bg, legacy + round mipmaps, fastlane icon.png (en-US, fa).
- [ ] Wordmark: simple two-tone 'Unbar' (Un blue, bar orange, Poppins Black) chosen direction — update site assets when releasing.
