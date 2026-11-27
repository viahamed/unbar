#!/usr/bin/env python3
"""Generate Unbar.xcodeproj (project.pbxproj + shared scheme) for the Unbar
iOS app, from the files on disk.

Run from ios/:  python3 tools/generate_project.py

UUIDs are deterministic (md5 of a stable key), so re-running produces the
same project. The script scans the source trees, so a file added to
Unbar/Sources, Shared or UnbarTunnel is picked up automatically.

The project has two targets:
  Unbar         — the SwiftUI app (iOS 14, Swift 5)
  UnbarTunnel   — the NEPacketTunnelProvider Network Extension

Both targets link UnbarCore.xcframework, which CI builds first
(`gomobile bind -target ios -o ios/UnbarCore.xcframework ./core`).
It does not exist on a Linux checkout; that is expected.
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # ios/
APP_DIR = "Unbar"
EXT_DIR = "UnbarTunnel"
SHARED_DIR = "Shared"
GROUP_ID = "group.org.breakthebarriers.unbar"


def uuid(key):
    return hashlib.md5(key.encode("utf-8")).hexdigest()[:24].upper()


# ---------------------------------------------------------------- files

def swift_files(rel_dir):
    out = []
    for base, _dirs, files in os.walk(os.path.join(ROOT, rel_dir)):
        for name in sorted(files):
            if name.endswith(".swift"):
                out.append(os.path.relpath(os.path.join(base, name), ROOT))
    return sorted(out)


APP_SWIFT = swift_files(APP_DIR)          # AppDelegate, SceneDelegate, Sources/**
EXT_SWIFT = swift_files(EXT_DIR)          # PacketTunnelProvider.swift
SHARED_SWIFT = swift_files(SHARED_DIR)    # SharedSettings, SharedLog

APP_RESOURCES = [
    (APP_DIR + "/Assets.xcassets", "folder.assetcatalog"),
    (APP_DIR + "/LaunchScreen.storyboard", "file.storyboard"),
]

# ---------------------------------------------------------------- model

objects = {}          # uuid -> (isa, dict, comment)
sections = {}         # isa -> [uuid]


def add(isa, key, fields, comment=""):
    uid = uuid(key)
    assert uid not in objects, "duplicate uuid for %s" % key
    objects[uid] = (isa, fields, comment)
    sections.setdefault(isa, []).append(uid)
    return uid


def fileref(path, file_type, name=None, source_tree="<group>", explicit=False):
    key = "fileref:" + path
    fields = {
        "isa": "PBXFileReference",
        ("explicitFileType" if explicit else "lastKnownFileType"): file_type,
        "path": os.path.basename(path) if source_tree == "<group>" else path,
        "sourceTree": source_tree,
    }
    if name is not None:
        fields["name"] = name
    if source_tree == "<group>":
        # Path is relative to the parent group; keep the leaf name.
        fields["path"] = os.path.basename(path)
    return add("PBXFileReference", key, fields, os.path.basename(path))


def buildfile(target, path, settings=None):
    key = "buildfile:%s:%s" % (target, path)
    ref = FILE_REFS[path]
    fields = {"isa": "PBXBuildFile", "fileRef": ref}
    if settings is not None:
        fields["settings"] = settings
    return add("PBXBuildFile", key, fields, os.path.basename(path))


def group(name, path, children, source_tree="<group>"):
    key = "group:%s:%s" % (path, name)
    fields = {
        "isa": "PBXGroup",
        "children": children,
        "name": name,
        "sourceTree": source_tree,
    }
    if path:
        fields["path"] = path
    return add("PBXGroup", key, fields, name)


# ---------------------------------------------------------------- file refs

FILE_REFS = {}

for path in APP_SWIFT + EXT_SWIFT + SHARED_SWIFT:
    FILE_REFS[path] = fileref(path, "sourcecode.swift")

for path, ftype in [
    (APP_DIR + "/Info.plist", "text.plist.xml"),
    (APP_DIR + "/Unbar.entitlements", "text.plist.xml"),
    (EXT_DIR + "/Info.plist", "text.plist.xml"),
    (EXT_DIR + "/UnbarTunnel.entitlements", "text.plist.xml"),
    (APP_DIR + "/Assets.xcassets", "folder.assetcatalog"),
    (APP_DIR + "/LaunchScreen.storyboard", "file.storyboard"),
]:
    FILE_REFS[path] = fileref(path, ftype)

# Localizable.strings variant group children: path is the .lproj file,
# never the bare language code (Xcode resolves the path relative to
# the parent group and looks for Unbar/en or Unbar/fa, which do not
# exist — the build fails with "Build input file cannot be found").
STR_EN = add("PBXFileReference", "fileref:strings:en",
             {"isa": "PBXFileReference", "lastKnownFileType": "text.plist.strings",
              "name": "en", "path": "en.lproj/Localizable.strings", "sourceTree": "<group>"}, "en")
STR_FA = add("PBXFileReference", "fileref:strings:fa",
             {"isa": "PBXFileReference", "lastKnownFileType": "text.plist.strings",
              "name": "fa", "path": "fa.lproj/Localizable.strings", "sourceTree": "<group>"}, "fa")
STRINGS_GROUP = add("PBXVariantGroup", "variant:Localizable.strings",
                    {"isa": "PBXVariantGroup", "children": [STR_EN, STR_FA],
                     "name": "Localizable.strings", "sourceTree": "<group>"},
                    "Localizable.strings")

# The gomobile core: built by CI before xcodebuild; absent on Linux.
CORE_REF = add("PBXFileReference", "fileref:UnbarCore.xcframework",
               {"isa": "PBXFileReference", "lastKnownFileType": "wrapper.xcframework",
                "name": "UnbarCore.xcframework", "path": "UnbarCore.xcframework",
                "sourceTree": "SOURCE_ROOT"}, "UnbarCore.xcframework")

# System framework.
NE_REF = add("PBXFileReference", "fileref:NetworkExtension.framework",
             {"isa": "PBXFileReference", "lastKnownFileType": "wrapper.framework",
              "name": "NetworkExtension.framework", "path": "NetworkExtension.framework",
              "sourceTree": "SDKROOT"}, "NetworkExtension.framework")

APP_PRODUCT = add("PBXFileReference", "fileref:product:Unbar.app",
                  {"isa": "PBXFileReference", "explicitFileType": "wrapper.application",
                   "includeInIndex": "0", "path": "Unbar.app",
                   "sourceTree": "BUILT_PRODUCTS_DIR"}, "Unbar.app")
EXT_PRODUCT = add("PBXFileReference", "fileref:product:UnbarTunnel.appex",
                  {"isa": "PBXFileReference", "explicitFileType": "wrapper.app-extension",
                   "includeInIndex": "0", "path": "UnbarTunnel.appex",
                   "sourceTree": "BUILT_PRODUCTS_DIR"}, "UnbarTunnel.appex")

# ---------------------------------------------------------------- groups

def subgroup_files(rel_dir):
    """Group children for one source directory, split into subfolders."""
    subs = {}
    leaves = []
    for path in swift_files(rel_dir):
        rel = os.path.relpath(path, rel_dir)
        parts = rel.split(os.sep)
        if len(parts) == 1:
            leaves.append(FILE_REFS[path])
        else:
            subs.setdefault(parts[0], []).append(FILE_REFS[path])
    children = list(leaves)
    for sub in sorted(subs):
        children.append(group(sub, sub, subs[sub]))
    return children

app_sources_group = group("Sources", "Sources",
                          subgroup_files(APP_DIR + "/Sources"))
app_group_children = [
    FILE_REFS[APP_DIR + "/AppDelegate.swift"],
    FILE_REFS[APP_DIR + "/SceneDelegate.swift"],
    FILE_REFS[APP_DIR + "/Info.plist"],
    FILE_REFS[APP_DIR + "/Unbar.entitlements"],
    FILE_REFS[APP_DIR + "/Assets.xcassets"],
    FILE_REFS[APP_DIR + "/LaunchScreen.storyboard"],
    STRINGS_GROUP,
    app_sources_group,
]
APP_GROUP = group("Unbar", APP_DIR, app_group_children)

EXT_GROUP = group("UnbarTunnel", EXT_DIR, [
    FILE_REFS[EXT_DIR + "/PacketTunnelProvider.swift"],
    FILE_REFS[EXT_DIR + "/Info.plist"],
    FILE_REFS[EXT_DIR + "/UnbarTunnel.entitlements"],
])

SHARED_GROUP = group("Shared", SHARED_DIR,
                     [FILE_REFS[p] for p in SHARED_SWIFT])

PRODUCTS_GROUP = group("Products", "", [APP_PRODUCT, EXT_PRODUCT])

ROOT_GROUP = group("Unbar", "", [APP_GROUP, EXT_GROUP, SHARED_GROUP,
                                 CORE_REF, PRODUCTS_GROUP])

# ---------------------------------------------------------------- build phases

def sources_phase(target, paths):
    files = [buildfile(target, p) for p in paths]
    return add("PBXSourcesBuildPhase", "phase:sources:" + target,
               {"isa": "PBXSourcesBuildPhase", "buildActionMask": "2147483647",
                "files": files, "runOnlyForDeploymentPostprocessing": "0"},
               "Sources")

def frameworks_phase(target, refs):
    files = [buildfile(target, "framework:" + r) for r in refs]
    return add("PBXFrameworksBuildPhase", "phase:frameworks:" + target,
               {"isa": "PBXFrameworksBuildPhase", "buildActionMask": "2147483647",
                "files": files, "runOnlyForDeploymentPostprocessing": "0"},
               "Frameworks")

def resources_phase(target, refs):
    files = [buildfile(target, "resource:" + r) for r in refs]
    return add("PBXResourcesBuildPhase", "phase:resources:" + target,
               {"isa": "PBXResourcesBuildPhase", "buildActionMask": "2147483647",
                "files": files, "runOnlyForDeploymentPostprocessing": "0"},
               "Resources")

# Framework build-file refs need their own file refs; register pseudo paths.
FILE_REFS["framework:core"] = CORE_REF
FILE_REFS["framework:networkextension"] = NE_REF
FILE_REFS["resource:assets"] = FILE_REFS[APP_DIR + "/Assets.xcassets"]
FILE_REFS["resource:launchscreen"] = FILE_REFS[APP_DIR + "/LaunchScreen.storyboard"]
FILE_REFS["resource:strings"] = STRINGS_GROUP

APP_SOURCES = sources_phase("app", APP_SWIFT + SHARED_SWIFT)
APP_FRAMEWORKS = frameworks_phase("app", ["core", "networkextension"])
APP_RESOURCES = resources_phase("app", ["assets", "launchscreen", "strings"])

EXT_SOURCES = sources_phase("ext", EXT_SWIFT + SHARED_SWIFT)
EXT_FRAMEWORKS = frameworks_phase("ext", ["core", "networkextension"])
EXT_RESOURCES = resources_phase("ext", [])

# Embed the extension in the app bundle (PlugIns).
FILE_REFS["product:UnbarTunnel.appex"] = EXT_PRODUCT
EMBED_EXT_FILE = buildfile("app", "product:UnbarTunnel.appex",
                           settings={"ATTRIBUTES": ["CodeSignOnCopy",
                                                    "RemoveHeadersOnCopy"]})
EMBED_PHASE = add("PBXCopyFilesBuildPhase", "phase:embed:app",
                  {"isa": "PBXCopyFilesBuildPhase", "buildActionMask": "2147483647",
                   "dstPath": "", "dstSubfolderSpec": "13",
                   "files": [EMBED_EXT_FILE],
                   "name": "Embed App Extensions",
                   "runOnlyForDeploymentPostprocessing": "0"},
                  "Embed App Extensions")

# ---------------------------------------------------------------- targets

EXT_TARGET = uuid("target:ext")
APP_TARGET = uuid("target:app")

PROJECT_UID = uuid("project")
proxy = add("PBXContainerItemProxy", "proxy:ext",
            {"isa": "PBXContainerItemProxy", "containerPortal": PROJECT_UID,
             "proxyType": "1", "remoteGlobalIDString": EXT_TARGET,
             "remoteInfo": "UnbarTunnel"}, "PBXContainerItemProxy")
dependency = add("PBXTargetDependency", "dependency:app->ext",
                 {"isa": "PBXTargetDependency", "target": EXT_TARGET,
                  "targetProxy": proxy}, "UnbarTunnel")

APP_CONFIGS = uuid("configlist:app")
EXT_CONFIGS = uuid("configlist:ext")

app_target = add("PBXNativeTarget", "target:app",
                 {"isa": "PBXNativeTarget",
                  "buildConfigurationList": APP_CONFIGS,
                  "buildPhases": [APP_SOURCES, APP_FRAMEWORKS, APP_RESOURCES, EMBED_PHASE],
                  "buildRules": [],
                  "dependencies": [dependency],
                  "name": "Unbar",
                  "productName": "Unbar",
                  "productReference": APP_PRODUCT,
                  "productType": "com.apple.product-type.application"},
                 "Unbar")
assert app_target == APP_TARGET
ext_target = add("PBXNativeTarget", "target:ext",
                 {"isa": "PBXNativeTarget",
                  "buildConfigurationList": EXT_CONFIGS,
                  "buildPhases": [EXT_SOURCES, EXT_FRAMEWORKS, EXT_RESOURCES],
                  "buildRules": [],
                  "dependencies": [],
                  "name": "UnbarTunnel",
                  "productName": "UnbarTunnel",
                  "productReference": EXT_PRODUCT,
                  "productType": "com.apple.product-type.app-extension"},
                 "UnbarTunnel")
assert ext_target == EXT_TARGET

# ---------------------------------------------------------------- configs

def build_settings(base):
    return base


COMMON_PROJECT = {
    "ALWAYS_SEARCH_USER_PATHS": "NO",
    "CLANG_ENABLE_MODULES": "YES",
    "GCC_C_LANGUAGE_STANDARD": "gnu11",
    "IPHONEOS_DEPLOYMENT_TARGET": "14.0",
    "SDKROOT": "iphoneos",
}

def config(name, key, settings):
    return add("XCBuildConfiguration", key,
               {"isa": "XCBuildConfiguration",
                "buildSettings": settings,
                "name": name}, name)

project_debug = config("Debug", "config:project:debug",
                       dict(COMMON_PROJECT, **{
                           "DEBUG_INFORMATION_FORMAT": "dwarf",
                           "ENABLE_TESTABILITY": "YES",
                           "GCC_OPTIMIZATION_LEVEL": "0",
                           "GCC_PREPROCESSOR_DEFINITIONS": ["DEBUG=1", "$(inherited)"],
                           "MTL_ENABLE_DEBUG_INFO": "YES",
                           "ONLY_ACTIVE_ARCH": "YES",
                           "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
                           "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
                       }))
project_release = config("Release", "config:project:release",
                         dict(COMMON_PROJECT, **{
                             "DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym",
                             "ENABLE_NS_ASSERTIONS": "NO",
                             "MTL_ENABLE_DEBUG_INFO": "NO",
                             "SWIFT_OPTIMIZATION_LEVEL": "-O",
                             "VALIDATE_PRODUCT": "YES",
                         }))

# One version for both targets (an app extension must match its host
# app): Info.plists read these build settings.
MARKETING_VERSION = "1.0.3"
CURRENT_PROJECT_VERSION = "4"

APP_COMMON = {
    "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
    "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
    "CODE_SIGN_ENTITLEMENTS": "Unbar/Unbar.entitlements",
    "CODE_SIGN_STYLE": "Automatic",
    "CURRENT_PROJECT_VERSION": CURRENT_PROJECT_VERSION,
    "FRAMEWORK_SEARCH_PATHS": ["$(inherited)", "$(PROJECT_DIR)"],
    "INFOPLIST_FILE": "Unbar/Info.plist",
    "IPHONEOS_DEPLOYMENT_TARGET": "14.0",
    "MARKETING_VERSION": MARKETING_VERSION,
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
    "PRODUCT_BUNDLE_IDENTIFIER": "org.breakthebarriers.unbar",
    "PRODUCT_NAME": "$(TARGET_NAME)",
    "SWIFT_VERSION": "5.0",
    "TARGETED_DEVICE_FAMILY": "1,2",
}
app_debug = config("Debug", "config:app:debug", dict(APP_COMMON))
app_release = config("Release", "config:app:release", dict(APP_COMMON))

EXT_COMMON = {
    "APPLICATION_EXTENSION_API_ONLY": "YES",
    "CODE_SIGN_ENTITLEMENTS": "UnbarTunnel/UnbarTunnel.entitlements",
    "CODE_SIGN_STYLE": "Automatic",
    "CURRENT_PROJECT_VERSION": CURRENT_PROJECT_VERSION,
    "FRAMEWORK_SEARCH_PATHS": ["$(inherited)", "$(PROJECT_DIR)"],
    "INFOPLIST_FILE": "UnbarTunnel/Info.plist",
    "IPHONEOS_DEPLOYMENT_TARGET": "14.0",
    "MARKETING_VERSION": MARKETING_VERSION,
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks",
                                "@executable_path/../../Frameworks"],
    "PRODUCT_BUNDLE_IDENTIFIER": "org.breakthebarriers.unbar.UnbarTunnel",
    "PRODUCT_NAME": "$(TARGET_NAME)",
    "SKIP_INSTALL": "YES",
    "SWIFT_VERSION": "5.0",
    "TARGETED_DEVICE_FAMILY": "1,2",
}
ext_debug = config("Debug", "config:ext:debug", dict(EXT_COMMON))
ext_release = config("Release", "config:ext:release", dict(EXT_COMMON))

def config_list(key, configs):
    return add("XCConfigurationList", key,
               {"isa": "XCConfigurationList",
                "buildConfigurations": configs,
                "defaultConfigurationIsVisible": "0",
                "defaultConfigurationName": "Release"},
               "Build configuration list")

PROJECT_CONFIGS = config_list("configlist:project",
                              [project_debug, project_release])
assert config_list("configlist:app", [app_debug, app_release]) == APP_CONFIGS
assert config_list("configlist:ext", [ext_debug, ext_release]) == EXT_CONFIGS

# ---------------------------------------------------------------- project

PROJECT = add("PBXProject", "project",
              {"isa": "PBXProject",
               "attributes": {
                   "LastUpgradeCheck": "1600",
                   "TargetAttributes": {
                       APP_TARGET: {"CreatedOnToolsVersion": "16.0"},
                       EXT_TARGET: {"CreatedOnToolsVersion": "16.0"},
                   },
               },
               "buildConfigurationList": PROJECT_CONFIGS,
               "compatibilityVersion": "Xcode 14.0",
               "developmentRegion": "en",
               "hasScannedForEncodings": "0",
               "knownRegions": ["en", "Base", "fa"],
               "mainGroup": ROOT_GROUP,
               "productRefGroup": PRODUCTS_GROUP,
               "projectDirPath": "",
               "projectRoot": "",
               "targets": [APP_TARGET, EXT_TARGET]},
              "Project object")

# ---------------------------------------------------------------- serialize

BARE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_./$-]*$")


def esc(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def fmt_str(text):
    if text == "":
        return '""'
    if BARE.match(text):
        return text
    return '"%s"' % esc(text)


def fmt_value(value, indent):
    if isinstance(value, dict):
        if not value:
            return "{}"
        lines = ["{"]
        for key in sorted(value):
            lines.append("\t" * (indent + 1) + "%s = %s;" % (
                fmt_str(str(key)), fmt_value(value[key], indent + 1)))
        lines.append("\t" * indent + "}")
        return "\n".join(lines)
    if isinstance(value, list):
        if not value:
            return "()"
        lines = ["("]
        for item in value:
            lines.append("\t" * (indent + 1) + "%s," % fmt_value(item, indent + 1))
        lines.append("\t" * indent + ")")
        return "\n".join(lines)
    if isinstance(value, bool):
        return "YES" if value else "NO"
    return fmt_str(str(value))


def comment_for(uid):
    _isa, _fields, comment = objects[uid]
    return (" /* %s */" % comment) if comment else ""


SECTION_ORDER = [
    "PBXBuildFile", "PBXContainerItemProxy", "PBXCopyFilesBuildPhase",
    "PBXFileReference", "PBXFrameworksBuildPhase", "PBXGroup",
    "PBXNativeTarget", "PBXProject", "PBXResourcesBuildPhase",
    "PBXSourcesBuildPhase", "PBXTargetDependency", "PBXVariantGroup",
    "XCBuildConfiguration", "XCConfigurationList",
]


def serialize():
    out = ["// !$*UTF8*$!", "{",
           "\tarchiveVersion = 1;",
           "\tclasses = {",
           "\t};",
           "\tobjectVersion = 56;",
           "\tobjects = {"]
    for isa in SECTION_ORDER:
        uids = sorted(sections.get(isa, []))
        if not uids:
            continue
        out.append("")
        out.append("\t/* Begin %s section */" % isa)
        for uid in uids:
            _isa, fields, comment = objects[uid]
            rendered = fmt_value(fields, 3)
            # fmt_value renders the dict with 3-tab base indent; re-indent lines
            lines = rendered.split("\n")
            body = "\n".join(lines)
            out.append("\t\t%s%s = %s;" % (uid, comment_for(uid), body))
        out.append("\t/* End %s section */" % isa)
    out.append("\t};")
    out.append("\trootObject = %s%s;" % (PROJECT, comment_for(PROJECT)))
    out.append("}")
    return "\n".join(out) + "\n"


def scheme():
    def ref(target_uid, name, product):
        return ('<BuildableReference BuildableIdentifier="primary" '
                'BlueprintIdentifier="%s" BuildableName="%s" '
                'BlueprintName="%s" ReferencedContainer="container:Unbar.xcodeproj"/>'
                % (target_uid, product, name))
    return """<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1600"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            %s
         </BuildActionEntry>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            %s
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         %s
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         %s
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
""" % (ref(APP_TARGET, "Unbar", "Unbar.app"),
       ref(EXT_TARGET, "UnbarTunnel", "UnbarTunnel.appex"),
       ref(APP_TARGET, "Unbar", "Unbar.app"),
       ref(APP_TARGET, "Unbar", "Unbar.app"))


def main():
    proj_dir = os.path.join(ROOT, "Unbar.xcodeproj")
    scheme_dir = os.path.join(proj_dir, "xcshareddata", "xcschemes")
    os.makedirs(scheme_dir, exist_ok=True)
    with open(os.path.join(proj_dir, "project.pbxproj"), "w") as f:
        f.write(serialize())
    with open(os.path.join(scheme_dir, "Unbar.xcscheme"), "w") as f:
        f.write(scheme())
    print("wrote Unbar.xcodeproj/project.pbxproj")
    print("wrote Unbar.xcodeproj/xcshareddata/xcschemes/Unbar.xcscheme")
    print("app swift files: %d, ext: %d, shared: %d"
          % (len(APP_SWIFT), len(EXT_SWIFT), len(SHARED_SWIFT)))
    # Sanity: every referenced on-disk file exists (except the CI-built xcframework).
    missing = []
    for path in APP_SWIFT + EXT_SWIFT + SHARED_SWIFT:
        if not os.path.exists(os.path.join(ROOT, path)):
            missing.append(path)
    for path in [APP_DIR + "/Info.plist", APP_DIR + "/Unbar.entitlements",
                 APP_DIR + "/Assets.xcassets", APP_DIR + "/LaunchScreen.storyboard",
                 APP_DIR + "/en.lproj/Localizable.strings",
                 APP_DIR + "/fa.lproj/Localizable.strings",
                 EXT_DIR + "/Info.plist", EXT_DIR + "/UnbarTunnel.entitlements"]:
        if not os.path.exists(os.path.join(ROOT, path)):
            missing.append(path)
    if missing:
        print("MISSING: %s" % missing)
        sys.exit(1)
    print("all referenced files exist (UnbarCore.xcframework is built by CI)")


if __name__ == "__main__":
    main()
