# Unbar — iOS app

SwiftUI app + Network Extension for iOS 14 and later. It uses the same
shared Go core as the Android app (`../core`, Soar v0.2.5),
compiled with gomobile as an `.xcframework`.

## Build

```sh
# From the repo root, on macOS with Xcode installed:
gomobile bind -target ios -o ios/UnbarCore.xcframework ./core
cd ios
xcodebuild -project Unbar.xcodeproj -scheme Unbar \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

CI (`.github/workflows/build.yml`, `ios` job) does exactly this on a
macOS runner. **There is no macOS/Xcode on the Linux VM, so the app
cannot be compiled locally** — CI is the verification. The project was
checked on Linux as far as possible: the `project.pbxproj` parses as a
valid OpenStep plist and every reference resolves
(`tools/verify_ios.py`), all Swift files have balanced delimiters and
use only iOS 14 APIs, and every localized string exists in both
languages. Run `python3 tools/verify_ios.py` after changing anything.

To regenerate the Xcode project after adding/removing files:

```sh
cd ios
python3 tools/generate_project.py   # also re-runs the existence checks
```

## Structure

```
Unbar.xcodeproj/              hand-generated (see tools/generate_project.py)
  project.pbxproj
  xcshareddata/xcschemes/Unbar.xcscheme
Unbar/                        app target (bundle org.breakthebarriers.unbar)
  AppDelegate.swift           @UIApplicationMain (delegate lifecycle)
  SceneDelegate.swift         hosts the SwiftUI root, handles unbar:// links
  Info.plist                  scene manifest, unbar URL scheme, localizations
  Unbar.entitlements          App Group only
  Assets.xcassets/            AppIcon (light + dark), Logo, AccentColor, BrandBlue
  LaunchScreen.storyboard
  en.lproj/ fa.lproj/         Localizable.strings (every string, both languages)
  Sources/
    Models/                   Server, AppSettings, LocalizationManager,
                              ThemeManager, TabRouter
    VPN/                      VPNManager, StatsStore, ConfigCodec, KeychainHelper
    Views/                    ContentView, HomeView, ServersView,
                              ServerEditView, SettingsView, AboutView,
                              LogsView, QRCodeView, Components
Shared/                       compiled into BOTH targets
  SharedSettings.swift        App Group id, TunnelConfig, StatsSnapshot
  SharedLog.swift             capped shared log (300 lines)
UnbarTunnel/                  extension target (org.breakthebarriers.unbar.UnbarTunnel)
  PacketTunnelProvider.swift  NEPacketTunnelProvider: starts the Go core,
                              attaches the TUN fd, applies network settings
  Info.plist                  packet-tunnel NSExtension
  UnbarTunnel.entitlements    packet-tunnel-provider + App Group
tools/
  generate_project.py         generates the .xcodeproj (deterministic UUIDs)
  verify_ios.py               Linux-side verification (see above)
```

## How the two processes talk

* **App Group** `group.org.breakthebarriers.unbar` (shared UserDefaults):
  the app stores servers + settings; the extension reads the tunnel
  config from the `NETunnelProviderProtocol` provider configuration and
  writes live stats (`StatsSnapshot`) and log lines back.
* **App → extension:** `VPNManager` configures a
  `NETunnelProviderManager` (bundle id
  `org.breakthebarriers.unbar.UnbarTunnel`) and starts/stops it.
* **Extension → app:** stats JSON once a second; the app derives
  speeds from deltas and accumulates daily/monthly usage.
* **Server testing / auto-select fastest:** the app also links the Core
  framework and runs a throwaway tunnel in its own process (Start +
  Test + Stop, no TUN) — that is how "Test" and "Auto-select fastest"
  work without involving the extension.

## The Go core API (verified, not assumed)

The ObjC header was generated on Linux with
`gobind -lang=objc github.com/viahamed/unbar/core` and the
Swift calls match it exactly. Package `core` → framework `Core`,
class `CoreTunnel`, constructor `CoreNewTunnel() -> CoreTunnel?`:

| Go | Swift (from the generated header) |
| --- | --- |
| `NewTunnel()` | `CoreNewTunnel()` |
| `Start(zone, serverKeyB64, resolvers, discoveryEnabled, country, tcpMode)` | `start(_:serverKeyB64:resolvers:discoveryEnabled:country:tcpMode:) throws` |
| `AttachTun(fd int)` | `attachTun(_: Int) throws` |
| `DetachTun()` | `detachTun() throws` |
| `StartSocks(port, username, password)` | `startSocks(_:username:password:) throws` |
| `StopSocks()` | `stopSocks() throws` |
| `SetBandwidthLimit(up, down int64)` | `setBandwidthLimit(_:downBytesPerSec:)` |
| `SetGeoBypass(enabled)` | `setGeoBypass(_:)` |
| `SetBypassDomains(domains)` | `setBypassDomains(_:)` |
| `Stats()` | `stats() -> String` (JSON) |
| `Test()` | `test() -> String` (`""` = ok) |
| `Stop()` | `stop() throws` |

`Tunnel.Dial` is skipped by gobind (unsupported types) — the app does
not use it. The TUN data plane is the core's minimal pure-Go TCP/IP
stack (`core/tunstack.go`); there is no gVisor/netstack dependency
anywhere on the Swift side.

## The TUN file descriptor

`NEPacketTunnelProvider` has no public accessor for the utun fd, so
`PacketTunnelProvider` tries, in order:

1. **Direct fd** — the packet flow's private `socket` ivar, whose
   `fileDescriptor` ivar is the utun fd (the ivar names used by
   open-source packet-tunnel providers). Read with the Objective-C
   runtime, never KVC, so a renamed ivar yields `nil` instead of an
   exception; the fd is validated with `fcntl(fd, F_GETFD)`.
2. **Packet bridge** (fully public API) — a `SOCK_DGRAM` Unix
   socketpair; one end goes to `AttachTun`, packets are shuttled
   between the other end and `packetFlow`. `SOCK_DGRAM` preserves
   packet boundaries and the core only does plain `Read`/`Write` on
   the fd, so the socket end behaves exactly like a TUN fd to it.

The path used is written to the log. Network settings: `10.8.0.2/32`,
default route `0.0.0.0/0`, DNS `10.8.0.1` (all domains), MTU 1500.

## iOS differences from Android (as agreed)

* **No per-app routing.** iOS per-app VPN is
  enterprise/MDM-only. The iOS equivalent of always-on is **on-demand
  VPN** (`NEOnDemandRuleConnect`); the tunnel cancels with an error on
  repeated health-check failure and the system restarts it when
  on-demand is on.
* **SOCKS is secondary.** In SOCKS mode the extension starts the core
  with no included routes and runs the SOCKS5 listener on
  `127.0.0.1:port`. iOS backgrounding limits what other apps can do
  with a loopback proxy, so VPN is the primary mode and the UI says so.
* Everything else is the same: Discovery/Pool/Manual resolvers, proxy
  settings, bandwidth limits, Iran geo bypass, auto-select fastest,
  auto-reconnect + health checks, live stats, config import/export,
  capped logs, Persian RTL + dark mode, Model B dashboard with the
  connected server's IP in the status card.

## Privacy rules baked in

* The app ships with **no pre-configured server** — no zone, key or IP
  is hardcoded anywhere (checked by `verify_ios.py`).
* The SOCKS password lives in the **Keychain** only. It is passed to
  the extension inside the VPN provider configuration at connect time
  and is **never** in UserDefaults, logs, or exported configs.
* Config export is a `unbar://add?c=...` link (zone, key, resolvers)
  shown as a QR code or copyable text; import accepts a pasted link.

## iOS 14 notes

* Deployment target is **14.0** (iPhone 6s and later — the iOS 14
  device floor). The app uses `@ObservedObject`/`@EnvironmentObject`,
  Combine, completion handlers, `NavigationView`,
  `UIActivityIndicatorView`, and window `overrideUserInterfaceStyle`
  (via `connectedScenes`) for the manual light/dark setting. `.task`
  (iOS 15) and `NavigationStack` (iOS 16) are still avoided.
* The app lifecycle is `UIApplicationDelegate` + `UISceneDelegate`
  with a `UIHostingController` (the delegate pattern, kept for the
  shared scene handling).
* In-app language (English/Persian) is implemented by loading the
  right `.lproj` bundle directly; RTL layout comes from the
  `layoutDirection` environment value.
* Dark app icons use the iOS 18+ `luminosity: dark` appearances in the
  app icon set; they are ignored on older iOS.
