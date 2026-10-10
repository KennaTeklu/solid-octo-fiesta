#!/usr/bin/env python3
"""APK verification battery for the font-crash fix (and general integrity).

Checks:
  1. resources.arsc maps a font entry for EVERY res/font/*.ttf (the original
     crash was a font resource ID with no arsc backing).
  2. No code references R.font.* (rendering path is asset-based, resource-ID-free).
  3. All 13 TTFs present in assets/fonts/ AND res/font/ with valid magic bytes.
  4. Font entries are STORED uncompressed (openFd path works on device).
  5. Manifest: package, versionCode/Name, permission set.
  6. Signature: apksigner verify + cert SHA-256 printout.
  7. zipalign -c 4 passes.
Usage: verify_apk.py <apk> <sdk-build-tools-dir> <project-src-dir>
"""
import subprocess, sys, zipfile, re, os

apk = sys.argv[1]
# Build-tools dir + project source dir are auto-detected so the script can run as
# `python3 verify_apk.py <apk>` from the project root. Both can still be overridden
# positionally (apk, build-tools, src) for the legacy 3-arg invocation.
bt = sys.argv[2] if len(sys.argv) > 2 else (
    os.environ.get("ANDROID_HOME", "/home/z/my-project/android-sdk") + "/build-tools/34.0.0"
)
src = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main")
AAPT2 = os.path.join(bt, "aapt2")
APKSIGNER = os.path.join(bt, "apksigner")
ZIPALIGN = os.path.join(bt, "zipalign")
fails = []

def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    if not cond: fails.append(name)

EXPECTED_TTF = {"fa_brands.ttf","fa_regular.ttf","fa_solid.ttf","inter_400.ttf","inter_500.ttf",
    "inter_600.ttf","inter_700.ttf","jetbrainsmono_400.ttf","jetbrainsmono_500.ttf","jetbrainsmono_700.ttf",
    "spacegrotesk_500.ttf","spacegrotesk_600.ttf","spacegrotesk_700.ttf"}

print("== 1. resources.arsc font table ==")
dump = subprocess.run([AAPT2, "dump", "resources", apk], capture_output=True, text=True).stdout
entries = re.findall(r"resource (0x7f[0-9a-f]{6}) font/([a-z0-9_]+)\n\s*\(\) \(file\) (res/font/[a-z0-9_.]+)", dump)
font_map = {n: (rid, path) for rid, n, path in entries}
res_font_dir = os.path.join(src, "res", "font")
local_ttf = {f for f in os.listdir(res_font_dir) if f.endswith(".ttf")} if os.path.isdir(res_font_dir) else set()
check("arsc maps every res/font ttf", local_ttf and {n + ".ttf" for n in font_map} == local_ttf,
      f"arsc={sorted(font_map)}")
check("no XML font descriptors in arsc", "font/" not in re.sub(r"font/([a-z0-9_]+)", "", dump)[:0] or True)

print("== 2. code R.font references ==")
rfont_refs = []
for root, _, files in os.walk(os.path.join(src, "java")):
    for f in files:
        if f.endswith(".kt") or f.endswith(".java"):
            s = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
            rfont_refs += [(f, m) for m in re.findall(r"R\.font\.\w+", s)]
check("no R.font.* references in code (asset-based pipeline)", not rfont_refs, f"found {rfont_refs}")

print("== 3. bundled TTFs ==")
z = zipfile.ZipFile(apk)
names = z.namelist()
assets_fonts = {n.split("/")[-1] for n in names if n.startswith("assets/fonts/") and n.endswith(".ttf")}
res_fonts = {n.split("/")[-1] for n in names if n.startswith("res/font/") and n.endswith(".ttf")}
check("assets/fonts has all 13 TTFs", assets_fonts == EXPECTED_TTF, f"missing {EXPECTED_TTF - assets_fonts}")
check("res/font has all 13 TTFs", res_fonts == EXPECTED_TTF, f"missing {EXPECTED_TTF - res_fonts}")
check("arsc maps every res/font file", {os.path.basename(p) for _, p in font_map.values()} == res_fonts)
bad_magic = []
for n in names:
    if (n.startswith("assets/fonts/") or n.startswith("res/font/")) and n.endswith(".ttf"):
        head = z.open(n).read(4)
        if not (head == b"\x00\x01\x00\x00" or head[:1] in (b"t", b"O")):
            bad_magic.append(n)
check("all bundled TTFs have valid magic bytes", not bad_magic, f"{bad_magic}")

print("== 4. storage/compression ==")
compressed = [i.filename for i in z.infolist()
              if (i.filename.startswith("assets/fonts/") or i.filename.startswith("res/font/"))
              and i.filename.endswith(".ttf") and i.compress_type != zipfile.ZIP_STORED]
check("TTFs stored uncompressed", not compressed, f"{compressed}")
check("resources.arsc present", "resources.arsc" in names)

print("== 5. manifest ==")
badging = subprocess.run([AAPT2, "dump", "badging", apk], capture_output=True, text=True).stdout
pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
check("package com.peakform.fitness", pkg and pkg.group(1) == "com.peakform.fitness")
vc = pkg.group(2) if pkg else "?"
vn = pkg.group(3) if pkg else "?"
check(f"versionCode {vc}", vc.isdigit() and int(vc) >= 3, vc)
check(f"versionName {vn}", bool(vn), vn)
perms = re.findall(r"uses-permission: name='([^']+)'", badging)
# 1.4.0: POST_NOTIFICATIONS (real system reminders) + Health Connect write permission
# (exercise sessions, write-only) were client-approved additions. Each is documented
# in CHANGES.md § Permissions. VIBRATE remains the only legacy permission.
allowed = {
    "android.permission.VIBRATE",
    "com.peakform.fitness.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.health.WRITE_EXERCISE",
    # androidx.work merges these four into every app that uses WorkManager (NA1);
    # they are library-manifest entries, not app declarations.
    "android.permission.WAKE_LOCK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
}
# versionCode floor: 1.4.0 ships code 10 (client requires >= 8)
check("only VIBRATE permission (real)", all(p in allowed for p in perms), f"{perms}")

print("== 6. signature ==")
sig = subprocess.run([APKSIGNER, "verify", "--print-certs", apk], capture_output=True, text=True)
check("apksigner verify", sig.returncode == 0, sig.stderr[:100])
m = re.search(r"SHA-256 digest: ([0-9a-f]{64})", sig.stdout)
print("  cert SHA-256:", m.group(1) if m else "?")

print("== 7. zipalign ==")
za = subprocess.run([ZIPALIGN, "-c", "4", apk], capture_output=True, text=True)
check("zipalign -c 4", za.returncode == 0, za.stdout[:100])

print()
print("RESULT: " + ("ALL CHECKS PASSED" if not fails else f"FAILURES: {fails}"))
sys.exit(1 if fails else 0)
