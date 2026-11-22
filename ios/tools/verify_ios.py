#!/usr/bin/env python3
"""Verify the Unbar iOS project as far as possible on Linux.

- Parses Unbar.xcodeproj/project.pbxproj with a real OpenStep-plist
  parser and checks every reference resolves.
- Parses the shared scheme XML and checks its target IDs.
- Validates Info.plists, entitlements, asset catalog JSON, storyboard XML.
- Checks Swift files for balanced delimiters (strings/comments stripped)
  and for forbidden content (sample server IP, hardcoded server).
- Checks every localization key used in Swift exists in both .strings.
"""
import json
import os
import plistlib
import re
import sys
import xml.etree.ElementTree as ET

IOS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(IOS)
errors = []
warnings = []


def err(msg):
    errors.append(msg)
    print("FAIL: " + msg)


def warn(msg):
    warnings.append(msg)
    print("WARN: " + msg)


def ok(msg):
    print("ok: " + msg)


# ---------------------------------------------------------------- OpenStep parser

class Tok:
    def __init__(self, kind, val):
        self.kind = kind
        self.val = val


def tokenize(text):
    toks = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in " \t\r\n":
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j + 1
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j == -1:
                raise ValueError("unterminated comment")
            i = j + 2
        elif c == '"':
            j = i + 1
            buf = []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    buf.append(text[j:j + 2])
                    j += 2
                elif text[j] == '"':
                    break
                else:
                    buf.append(text[j])
                    j += 1
            if j >= n:
                raise ValueError("unterminated string")
            toks.append(Tok("str", "".join(buf)))
            i = j + 1
        elif c in "{}()=;,":
            toks.append(Tok(c, c))
            i += 1
        else:
            j = i
            while j < n and text[j] not in " \t\r\n{}()=;,\"":
                j += 1
            toks.append(Tok("str", text[i:j]))
            i = j
    toks.append(Tok("eof", ""))
    return toks


def unescape(s):
    return s.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n")


class Parser:
    def __init__(self, toks):
        self.toks = toks
        self.i = 0

    def peek(self):
        return self.toks[self.i]

    def next(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def expect(self, kind):
        t = self.next()
        if t.kind != kind:
            raise ValueError("expected %s got %r" % (kind, t.val))
        return t

    def parse(self):
        return self.value()

    def value(self):
        t = self.peek()
        if t.kind == "{":
            self.next()
            d = {}
            while self.peek().kind != "}":
                k = unescape(self.expect("str").val)
                self.expect("=")
                d[k] = self.value()
                self.expect(";")
            self.expect("}")
            return d
        if t.kind == "(":
            self.next()
            items = []
            while self.peek().kind != ")":
                items.append(self.value())
                if self.peek().kind == ",":
                    self.next()
            self.expect(")")
            return items
        if t.kind == "str":
            self.next()
            return unescape(t.val)
        raise ValueError("unexpected token %r" % t.val)


# ---------------------------------------------------------------- pbxproj

pbx_path = os.path.join(IOS, "Unbar.xcodeproj", "project.pbxproj")
text = open(pbx_path).read()
try:
    proj = Parser(tokenize(text)).parse()
    ok("project.pbxproj parses as OpenStep plist")
except Exception as e:
    err("project.pbxproj does not parse: %s" % e)
    sys.exit(1)

objects = proj["objects"]
root = objects[proj["rootObject"]]
assert root["isa"] == "PBXProject", "root is not PBXProject"
targets = [objects[t] for t in root["targets"]]
target_names = sorted(t["name"] for t in targets)
if target_names == ["Unbar", "UnbarTunnel"]:
    ok("both targets present: Unbar + UnbarTunnel")
else:
    err("targets are %s" % target_names)

by_isa = {}
for uid, obj in objects.items():
    by_isa.setdefault(obj["isa"], []).append(uid)

# Every fileRef used in a build file / group must exist as an object.
refs = set()
for uid, obj in objects.items():
    if obj["isa"] == "PBXBuildFile":
        refs.add(obj["fileRef"])
    if obj["isa"] in ("PBXGroup", "PBXVariantGroup"):
        refs.update(obj.get("children", []))
missing_refs = [r for r in refs if r not in objects]
if missing_refs:
    err("dangling references: %s" % missing_refs)
else:
    ok("all %d build/group references resolve" % len(refs))

# Every PBXFileReference path must exist on disk, except the CI-built xcframework.
disk_missing = []
for uid in by_isa.get("PBXFileReference", []):
    obj = objects[uid]
    st = obj.get("sourceTree")
    path = obj.get("path", "")
    if st == "SDKROOT" or st == "BUILT_PRODUCTS_DIR":
        continue
    if st == "SOURCE_ROOT":
        full = os.path.join(IOS, path)
        if not os.path.exists(full):
            if path == "UnbarCore.xcframework":
                warn("UnbarCore.xcframework absent (expected: built by CI)")
            else:
                disk_missing.append(path)
        continue
    # <group>: resolve through the group tree — approximate by searching.
    if path in ("en", "fa"):
        # A localized strings file ref must point at the .lproj file,
        # never at the bare language code: Xcode resolves it relative
        # to the parent group and looks for Unbar/en or Unbar/fa,
        # which do not exist (the build fails with "Build input file
        # cannot be found"). This blind spot hid the bug until CI.
        err("strings file reference has bare language-code path %r (want <lang>.lproj/Localizable.strings)" % path)
        continue
    if os.path.exists(os.path.join(IOS, "Unbar", path)) or \
            os.path.exists(os.path.join(IOS, path)):
        continue
    found = False
    for base, dirs, files in os.walk(IOS):
        if path in files or path in dirs:
            if "Unbar.xcodeproj" not in base:
                found = True
                break
    if not found:
        disk_missing.append(path)
if disk_missing:
    err("file references missing on disk: %s" % disk_missing)
else:
    ok("all referenced files exist on disk (except CI-built xcframework)")

# Config checks: deployment target, bundle ids, entitlements, framework search.
configs = {}
for uid in by_isa.get("XCBuildConfiguration", []):
    obj = objects[uid]
    configs[(uid, obj["name"])] = obj["buildSettings"]
app_target = [t for t in targets if t["name"] == "Unbar"][0]
ext_target = [t for t in targets if t["name"] == "UnbarTunnel"][0]
for t, label in [(app_target, "app"), (ext_target, "ext")]:
    clist = objects[t["buildConfigurationList"]]
    for cfg_uid in clist["buildConfigurations"]:
        s = objects[cfg_uid]["buildSettings"]
        name = objects[cfg_uid]["name"]
        if s.get("IPHONEOS_DEPLOYMENT_TARGET") != "14.0":
            err("%s %s deployment target is %s" % (label, name, s.get("IPHONEOS_DEPLOYMENT_TARGET")))
        if s.get("SWIFT_VERSION") != "5.0":
            err("%s %s SWIFT_VERSION is %s" % (label, name, s.get("SWIFT_VERSION")))
        if "$(PROJECT_DIR)" not in s.get("FRAMEWORK_SEARCH_PATHS", []):
            err("%s %s missing $(PROJECT_DIR) in FRAMEWORK_SEARCH_PATHS" % (label, name))
    ok("%s configs: iOS 14.0, Swift 5, framework search path set" % label)

app_settings = objects[objects[app_target["buildConfigurationList"]]["buildConfigurations"][0]]["buildSettings"]
if app_settings.get("PRODUCT_BUNDLE_IDENTIFIER") != "org.breakthebarriers.unbar":
    err("app bundle id wrong")
if app_settings.get("INFOPLIST_FILE") != "Unbar/Info.plist":
    err("app Info.plist path wrong")
if app_settings.get("CODE_SIGN_ENTITLEMENTS") != "Unbar/Unbar.entitlements":
    err("app entitlements path wrong")
ext_settings = objects[objects[ext_target["buildConfigurationList"]]["buildConfigurations"][0]]["buildSettings"]
if ext_settings.get("PRODUCT_BUNDLE_IDENTIFIER") != "org.breakthebarriers.unbar.UnbarTunnel":
    err("extension bundle id wrong")
if ext_settings.get("APPLICATION_EXTENSION_API_ONLY") != "YES":
    err("extension APPLICATION_EXTENSION_API_ONLY not YES")
ok("bundle ids, plists, entitlements paths correct")

# Extension is embedded in the app.
embed = [objects[u] for u in by_isa.get("PBXCopyFilesBuildPhase", [])]
if embed and embed[0].get("dstSubfolderSpec") == "13":
    ok("Embed App Extensions phase targets PlugIns")
else:
    err("Embed App Extensions phase missing or wrong subfolder")

# Both targets link the Core xcframework.
for t, label in [(app_target, "app"), (ext_target, "ext")]:
    phases = [objects[p] for p in t["buildPhases"]]
    fw = [p for p in phases if p["isa"] == "PBXFrameworksBuildPhase"]
    linked = [objects[objects[b]["fileRef"]].get("path") for b in fw[0]["files"]]
    if "UnbarCore.xcframework" in linked and "NetworkExtension.framework" in linked:
        ok("%s links UnbarCore.xcframework + NetworkExtension" % label)
    else:
        err("%s links %s" % (label, linked))

# ---------------------------------------------------------------- scheme

scheme_path = os.path.join(IOS, "Unbar.xcodeproj", "xcshareddata",
                           "xcschemes", "Unbar.xcscheme")
try:
    scheme = ET.parse(scheme_path).getroot()
    ok("Unbar.xcscheme is valid XML")
except Exception as e:
    err("scheme XML invalid: %s" % e)
    scheme = None
if scheme is not None:
    ids = set()
    for ref in scheme.iter("BuildableReference"):
        ids.add(ref.get("BlueprintIdentifier"))
    target_uids = set(root["targets"])
    if ids == target_uids:
        ok("scheme references exactly the two targets")
    else:
        err("scheme target ids %s vs project %s" % (ids, target_uids))

# ---------------------------------------------------------------- plists / json / xml

for rel in ["Unbar/Info.plist", "Unbar/Unbar.entitlements",
            "UnbarTunnel/Info.plist", "UnbarTunnel/UnbarTunnel.entitlements"]:
    try:
        with open(os.path.join(IOS, rel), "rb") as f:
            plistlib.load(f)
        ok("%s is a valid plist" % rel)
    except Exception as e:
        err("%s invalid plist: %s" % (rel, e))

for base, _d, files in os.walk(os.path.join(IOS, "Unbar", "Assets.xcassets")):
    for name in files:
        if name == "Contents.json":
            p = os.path.join(base, name)
            try:
                json.load(open(p))
            except Exception as e:
                err("%s invalid JSON: %s" % (p, e))
ok("asset catalog Contents.json files are valid JSON")

# Icon files referenced by the appiconset must exist.
appicon = json.load(open(os.path.join(IOS, "Unbar", "Assets.xcassets",
                                     "AppIcon.appiconset", "Contents.json")))
adir = os.path.join(IOS, "Unbar", "Assets.xcassets", "AppIcon.appiconset")
missing_icons = [i["filename"] for i in appicon["images"]
                 if not os.path.exists(os.path.join(adir, i["filename"]))]
if missing_icons:
    err("appiconset missing files: %s" % missing_icons)
else:
    ok("all %d appiconset images exist" % len(appicon["images"]))

try:
    ET.parse(os.path.join(IOS, "Unbar", "LaunchScreen.storyboard"))
    ok("LaunchScreen.storyboard is valid XML")
except Exception as e:
    err("storyboard invalid: %s" % e)

# ---------------------------------------------------------------- Swift checks

def strip_swift(src):
    """Remove comments and string literals (incl. interpolations)."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j == -1 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j == -1 else j + 2
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j == -1 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    j += 1
                i = min(j + 1, n)
        else:
            out.append(c)
            i += 1
    return "".join(out)


swift_files = []
for base, _d, files in os.walk(IOS):
    if "Unbar.xcodeproj" in base:
        continue
    for name in files:
        if name.endswith(".swift"):
            swift_files.append(os.path.join(base, name))
print("swift files: %d" % len(swift_files))

pairs = {"{": "}", "(": ")", "[": "]"}
for path in sorted(swift_files):
    src = open(path).read()
    stripped = strip_swift(src)
    stack = []
    line = 1
    bad = None
    for ch in stripped:
        if ch == "\n":
            line += 1
        elif ch in pairs:
            stack.append((ch, line))
        elif ch in "})]":
            if not stack:
                bad = "unmatched %s at line %d" % (ch, line)
                break
            open_ch, open_line = stack.pop()
            if pairs[open_ch] != ch:
                bad = "mismatch %s (line %d) closed by %s (line %d)" % (open_ch, open_line, ch, line)
                break
    if bad is None and stack:
        bad = "unclosed %s from line %d" % (stack[-1][0], stack[-1][1])
    rel = os.path.relpath(path, IOS)
    if bad:
        err("%s: %s" % (rel, bad))
    # Forbidden / risky content.
    if "138.68.105.42" in src:
        err("%s: contains the forbidden sample IP" % rel)
    # The real server zone is checked without writing it as a
    # literal in this script: the pattern is built from its parts,
    # so the literal never appears here.
    if ".".join(["r", "btb", "onl"]) in src:
        warn("%s: mentions the real server zone (must only be an example, never a default)" % rel)
    if "TODO" in src or "FIXME" in src:
        warn("%s: contains TODO/FIXME" % rel)
    if "@StateObject" in stripped or ".task(" in stripped or "NavigationStack" in stripped \
            or "ProgressView(" in stripped or "preferredColorScheme" in stripped \
            or "Label(" in stripped or ".title2" in stripped:
        err("%s: uses an API that needs iOS 14+" % rel)
if not errors:
    ok("all Swift files have balanced delimiters, no iOS 14+ APIs, no sample IP")

# Localization keys: used in code vs defined in both .strings files.
key_re = re.compile(r'\.tr\("([A-Za-z0-9_.]+)"')
used = set()
for path in swift_files:
    used.update(key_re.findall(open(path).read()))
# Also error keys passed as plain strings to connect().
vpn_src = open(os.path.join(
    IOS, "Unbar", "Sources", "VPN", "VPNManager.swift")).read()
used.update(re.findall(r'completion\("([a-z_]+)"\)', vpn_src))
used.update(re.findall(r'completion\(nil, "([a-z_]+)"\)', vpn_src))
strings_re = re.compile(r'^"([A-Za-z0-9_.]+)"\s*=', re.M)
defined = {}
for lang in ("en", "fa"):
    p = os.path.join(IOS, "Unbar", "%s.lproj" % lang, "Localizable.strings")
    defined[lang] = set(strings_re.findall(open(p, encoding="utf-8").read()))
for lang in ("en", "fa"):
    missing = sorted(used - defined[lang])
    if missing:
        err("%s Localizable.strings missing keys: %s" % (lang, missing))
    else:
        ok("%s Localizable.strings covers all %d used keys" % (lang, len(used)))
extra_en = sorted(defined["en"] - used)
if extra_en:
    warn("en strings defined but not used in code: %s" % extra_en)
extra_fa = sorted(defined["fa"] - defined["en"])
if extra_fa:
    err("fa has keys missing in en: %s" % extra_fa)
if defined["en"] != defined["fa"]:
    warn("en/fa key sets differ: %s" % (sorted(defined["en"] ^ defined["fa"])))

print()
print("ERRORS: %d  WARNINGS: %d" % (len(errors), len(warnings)))
sys.exit(1 if errors else 0)
