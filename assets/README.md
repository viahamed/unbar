# Unbar — Brand Assets

Complete logo + app-icon package for the Unbar VPN app.
Built 2026-11-16 from the approved wordmark («همین عالیه»).

## Colours
- Deep blue (light theme "Un"): `#0B4F9E`
- Bright blue (dark theme "Un"): `#3B82F6`
- Orange (barrier stripes): `#FF7A1A`
- Dark navy background (dark theme): `#0D162E`
- Tagline grey: dark grey on light, light grey on dark

## Logo lockups — `logo/`
The full wordmark with the "by Break The Barriers" tagline, centred below.
- `unbar-logo-light.png` — 2000×2000, white background (light theme)
- `unbar-logo-dark.png` — 2000×2000, dark navy background (dark theme)
Use these for the splash screen, About page, website and any place the full
logo belongs. Light logo on light surfaces, dark logo on dark surfaces.

## App icon — `icon/`
The exact approved wordmark (no redesign), fitted into the square icon format:
the logo is cropped to its content and centred on a square canvas of the
matching background colour, so the wordmark fills the icon properly.
- `app-icon-light-1600.png` — light master, 1600×1600
- `app-icon-dark-1600.png` — dark master, 1600×1600
- `app-icon-light-foreground-1600.png` — transparent wordmark (light)
- `app-icon-dark-foreground-1600.png` — transparent wordmark (dark)

### Android — `icon/android/`
- `ic_launcher-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}.png` — legacy launcher icons
  (48 / 72 / 96 / 144 / 192 px), light + `-dark` variants.
- `ic_launcher_foreground.png` (432×432, transparent) +
  `ic_launcher_background.png` (432×432, solid white) — adaptive icon.
  `-dark` variants use the dark navy background.
  Place in `res/mipmap-anydpi-v26/` as `ic_launcher.xml` referencing these.

### iOS — `icon/ios/`
- `app-icon-1024.png` — App Store icon (required, 1024×1024, no transparency,
  no rounded corners — iOS applies the mask).
- `app-icon-{180,167,152,120,87,80,58,40,29,20}.png` — standard sizes.
  `-dark` variants are the dark-mode icons (iOS 18+).

### Stores — `icon/store/`
- `play-store-icon-512.png` — store listing icon (512×512).
- `app-store-icon-1024.png` — Apple App Store icon (1024×1024).
  `-dark` variants included for dark-mode store artwork.

## Notes
- All PNGs are exact-size, LANCZOS-resampled from the 1600px masters.
- The "U" stays readable down to 20px (smallest iOS size).
- Still to produce when the app UI exists: Play Store feature graphic
  (1024×500) and App Store / Play Store screenshots from the real app.
- Source of the icon design: `build_assets.py` in this folder (regenerates
  everything from the two generated icon files + the two logo PNGs).
