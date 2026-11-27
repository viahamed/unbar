# Unbar 1.0.3 — release notes draft (local, not published)

Base: upstream `viahamed/unbar` main 7f7ed59. Local commits only; nothing pushed, no GitHub release created.
Version: Android versionName 1.0.3 / versionCode 4 (F-Droid per-ABI builds 41/42/43, VercodeOperation 10*%c+1..3); iOS MARKETING_VERSION 1.0.3 / CURRENT_PROJECT_VERSION 4 (all targets, also in `ios/tools/generate_project.py`).

Nothing below has been built or tested on a device yet (no Android SDK / Xcode in the sandbox). Let CI build the branch and test on real devices before tagging.

## English

### App (Android + iOS)
1. **Per-network resolver memory.** Healthy resolvers are remembered per network as well as in the global pool. Cellular is keyed by the carrier MCC-MNC; Wi-Fi by a hash (SHA-256, 16 hex) of gateway / IPv4 subnet / IPv6 /64 prefix — read without the location permission, and the SSID is never read or stored. A network with no pool of its own (or no stable id) uses the global pool, which is still updated on every merge. At most 8 network pools are kept (LRU). Android: `NetworkCallback` in `UnbarVpnService` + `NetworkIdentity` in `ResolverHealthStore.kt`. iOS: `NWPathMonitor` in `PacketTunnelProvider` + `NetworkIdentity` in `Shared/ResolverHealthStore.swift`. On iOS 16.4+ CTCarrier no longer reports MCC-MNC, so cellular falls back to one shared cellular pool (`cell:any`).
2. *(core)* Shared DNS cache for both data planes (LRU, TTL-based expiry, TTL decrement, serve-stale per RFC 8767: when the upstream query fails, an entry up to 1 day past expiry is served with TTL 30 s).
3. *(core)* Client UDP socket buffers (4 MB read / 1 MB write requested, granted size logged once).
4. **Path diagnostics.** New panel in System & diagnostics (iOS: Settings > Path diagnostics): state, transport, RTT, loss, window, answer limit and name length per path, refreshed every 3 s while open, with Copy (a plain-text report for bug reports).
5. **Short-zone advice** in the add-server tutorial: a shorter tunnel zone leaves more room for data in each query.
6. **Tutorial:** resolver `.txt` Import is now explained in the Servers tutorial (import topic) and in the Settings resolvers topic.
7. **Import note example** is now `0.0.0.0/24`.
8. **iOS MTU Auto** reads the MTU of the active interface (NWPathMonitor `availableInterfaces`), falling back to the first non-loopback interface; 200-byte overhead and the 1280–1500 clamp are unchanged.
9. **DNS answer size.** Manual choices are Soar class boundaries: Auto / 512 / 752 / 992. Stored values migrate: 768 → 752, 1024 → 992, 1232 → Auto (1232 equals Auto). The UI and the log say the answer size applies on the next connect. *(core: accepts class boundaries 512+48k; legacy 768/1024 snap to 752/992.)*
10. **Resolver list check and range warning.** In Custom / Import mode the Resolvers card (below the scan stepper) shows how many resolvers the list gives and, when ranges wider than /20 are skipped or the list is capped at 5000, how many addresses are left out plus the core's per-range warnings — from the core's own expansion (`CheckResolverList`, no tunnel needed). After a scan, Stats() `resolverListDropped` / `resolverListWarnings` is still the fallback when the check is unavailable *(core: ranges also skip .0/.255)*.
11. **About redesign.** Blue hero card (app icon + Break The Barriers tiles, wordmark, tagline, chips "App v<version>", "Soar core v0.2.5" and "GPL-3.0"), a links card (Website unbar.cc, Telegram channel @Unbarapp, Source code), and a collapsible donation card (USDT · BEP20 and TON in full with copy buttons). Dark-theme Break The Barriers logo variant. Contrast tweaks: wordmark orange #FF9A55, 12 px wallet address, 44 px copy buttons.
12. **Scan over UDP or UDP + TCP.** New choice above Scan resolvers ("Scan over: UDP | UDP + TCP", persisted). UDP (default) is the unchanged pre-1.0.3 stage 1. UDP + TCP sends the same example.com A probe over UDP and over TCP/53 (2-byte length prefix) in parallel, with the same validation; a resolver passes when either answers, so a resolver costs at most one 2 s deadline either way. Resolvers that answered only over TCP get a TCP-only path in the stage-2 Soar vetting client (soar `StreamOnly`). The 30 s background pool probe uses the same mode, so TCP-only resolvers are not evicted by UDP-only probes. *(core: `Tunnel.SetScanTransport("udp" | "udp+tcp")`, Stats `scanTransport`.)*
13. **Transport tags.** Verified / remembered resolvers carry a `transport` field — `udp`, `tcp` or `udp+tcp` (union of the resolver's verified Soar paths; still one entry per resolver, faster RTT). Remembered rows show a small tag between `ip:port` and the ping: UDP (blue), TCP (orange), UDP·TCP. Backward compatible: entries saved before 1.0.3 have no transport and show no tag; JSON omits the field when unknown.
14. **TCP Mode text corrected** (label and subtitle, en + fa, both platforms; Android label moved to a string resource): "Adds a TCP/53 path for every resolver next to UDP. Helps when UDP is blocked, but uses more battery. When off, TCP is used only where UDP fails." (On = Soar TCP "on", off = "auto"; the old text wrongly said it *replaces* UDP.)
15. **Tutorial** (Android + iOS, en + fa) covers scan mode, transport tags, Pause/Stop, the list check, the corrected TCP Mode and the DNS cache.

*(core)* Resolver scan: 32-worker pool honouring Pause/Stop per probe, stricter probe validation; verified resolvers de-duplicated by address (UDP + TCP paths); healthy-probe cursor fixed after evictions.

Licence: GPL-3.0 (LICENSE; About chip; README; F-Droid description).

### Before release
- [ ] CI build (Android AAR + APKs, iOS xcframework + app) on the pushed branch.
- [ ] Rebuild the AAR / xcframework: the apps now call `Tunnel.setScanTransport` and `Core.checkResolverList` / `CoreCheckResolverList` (neither app compiles against bindings without these symbols; at runtime the Android calls are wrapped in `runCatching`).
- [ ] Device test: per-network pools (switch Wi-Fi ↔ mobile, two Wi-Fi networks), Path diagnostics, answer-size migration from 1.0.2 (768/1024/1232 stored), About in light/dark and fa/en, iOS MTU Auto log line.
- [ ] Device test: Scan over UDP vs UDP + TCP (scan time, TCP-only resolvers kept, tags UDP / TCP / UDP·TCP on remembered rows, old entries without tag), Custom list with a /16 range (count + left-out warning), TCP Mode text in fa/en.
- [ ] Build, sign, GitHub release v1.0.3 (not done here).

## فارسی

### برنامه (اندروید و iOS)
۱. **حافظهٔ ریزالور برای هر شبکه.** ریزالورهای سالم علاوه بر استخر کلی، برای هر شبکه جدا ذخیره می‌شوند. موبایل با MCC-MNC اپراتور شناخته می‌شود؛ وای‌فای با یک هش (SHA-256) از گیت‌وی / زیرشبکهٔ IPv4 / پیشوند ‎/64‎ ‏IPv6 — بدون دسترسی موقعیت مکانی، و نام شبکه (SSID) هرگز خوانده یا ذخیره نمی‌شود. شبکه‌ای که هنوز استخر خودش را ندارد از استخر کلی استفاده می‌کند؛ استخر کلی همچنان به‌روز می‌شود. حداکثر ۸ استخر شبکه نگه داشته می‌شود. در iOS 16.4 به بعد CTCarrier کد اپراتور را نمی‌دهد، پس همهٔ شبکه‌های موبایل یک استخر مشترک دارند.
۲. *(هسته)* کش مشترک DNS ‏(LRU، انقضا با TTL، کم کردن TTL، serve-stale: اگر کوئری اصلی شکست بخورد، پاسخی که تا ۱ روز از انقضایش گذشته با TTL ۳۰ ثانیه داده می‌شود).
۳. *(هسته)* بافرهای سوکت UDP کلاینت (۴ مگابایت خواندن / ۱ مگابایت نوشتن).
۴. **عیب‌یابی مسیرها.** پنل جدید در «سیستم و عیب‌یابی» (در iOS: تنظیمات > عیب‌یابی مسیرها): وضعیت، انتقال، RTT، اتلاف، پنجره، سقف پاسخ و طول نام هر مسیر، هر ۳ ثانیه به‌روز، با دکمهٔ کپی.
۵. **پیشنهاد zone کوتاه** در آموزش افزودن سرور: zone کوتاه‌تر یعنی جای بیشتر برای داده در هر کوئری.
۶. **آموزش:** وارد کردن لیست ریزالور از فایل ‎.txt‎ حالا در آموزش سرورها و در موضوع ریزالورهای آموزش تنظیمات توضیح داده شده است.
۷. **مثال یادداشت وارد کردن** حالا ‎0.0.0.0/24‎ است.
۸. **MTU خودکار در iOS** حالا MTU رابط شبکهٔ فعال (NWPathMonitor) را می‌خواند؛ اگر پیدا نشود مثل قبل اولین رابط غیر loopback. سربار ۲۰۰ بایت و بازهٔ ۱۲۸۰ تا ۱۵۰۰ تغییری نکرده است.
۹. **اندازهٔ جواب DNS.** گزینه‌های دستی پله‌های واقعی Soar هستند: خودکار / ۵۱۲ / ۷۵۲ / ۹۹۲. مقدارهای ذخیره‌شده منتقل می‌شوند: ۷۶۸←۷۵۲، ۱۰۲۴←۹۹۲، ۱۲۳۲←خودکار. رابط کاربری و لاگ می‌گویند که تغییر با اتصال بعدی اعمال می‌شود.
۱۰. **بررسی لیست و هشدار رنج‌های بزرگ.** در حالت سفارشی/وارد کردن، کارت ریزالورها (زیر مراحل اسکن) نشان می‌دهد لیست چند ریزالور می‌دهد و اگر رنجی بزرگ‌تر از ‎/20‎ کنار گذاشته شود یا لیست به ۵۰۰۰ محدود شود، چند آدرس کنار می‌رود — با همان گسترش هسته (‎CheckResolverList‎، بدون نیاز به تونل).
۱۱. **صفحهٔ «درباره»ٔ تازه.** کارت آبی (کاشی آیکون برنامه و Break The Barriers، wordmark، شعار، و چیپ‌های «App v<نسخه>»، «Soar core v0.2.5» و «GPL-3.0»)، کارت لینک‌ها (وب‌سایت، کانال تلگرام، کد منبع) و کارت جمع‌شوندهٔ حمایت مالی (آدرس کامل USDT · BEP20 و TON با دکمهٔ کپی). نسخهٔ تیرهٔ لوگوی Break The Barriers. کنتراست بیشتر: نارنجی wordmark ‏#FF9A55، آدرس کیف پول ۱۲ پیکسل، دکمهٔ کپی ۴۴ پیکسل.
۱۲. **اسکن روی UDP یا UDP + TCP.** گزینهٔ تازه بالای «اسکن ریزالورها» (ذخیره می‌شود). ‏UDP (پیش‌فرض) همان مرحلهٔ ۱ قبلی است. UDP + TCP همان پروب را هم‌زمان از UDP و TCP/53 می‌فرستد؛ ریزالوری که از هر کدام جواب درست بدهد قبول است. ریزالورهای فقط-TCP در مرحلهٔ ۲ مسیر TCP می‌گیرند، و پروب پس‌زمینهٔ استخر هم از همین حالت استفاده می‌کند.
۱۳. **برچسب انتقال.** کنار هر ریزالور ذخیره‌شده، بین ‎ip:port‎ و پینگ، برچسب UDP (آبی)، TCP (نارنجی) یا UDP·TCP دیده می‌شود. ورودی‌های قدیمی برچسب ندارند.
۱۴. **متن درست حالت TCP** (عنوان و توضیح، فارسی و انگلیسی، هر دو پلتفرم): «برای هر ریزالور، کنار UDP یک مسیر TCP/53 هم اضافه می‌کند. وقتی UDP مسدود است کمک می‌کند، ولی باتری بیشتری مصرف می‌کند. در حالت خاموش، TCP فقط جایی به کار می‌رود که UDP جواب ندهد.»
۱۵. **آموزش** (اندروید و iOS، فارسی و انگلیسی): حالت اسکن، برچسب‌های انتقال، مکث/توقف، بررسی لیست، متن درست حالت TCP و کش DNS.

*(هسته)* اسکن ریزالور: ۳۲ کارگر با احترام به توقف/مکث در هر پروب، اعتبارسنجی دقیق‌تر؛ حذف تکرار ریزالورهای تأییدشده بر اساس آدرس.

لایسنس: GPL-3.0.
