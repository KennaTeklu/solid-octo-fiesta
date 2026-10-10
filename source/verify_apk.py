#!/usr/bin/env python3
"""
verify_apk.py — Batch-3 APK self-check.

Runs the four sub-checks the client cares about:
  1. APK file exists and is non-empty.
  2. aapt2 dump badging shows the expected versionCode / versionName / package.
  3. apksigner verify passes AND the signer cert SHA-256 matches the
     required uber-apk-signer debug key.
  4. The APK's SHA-256 differs from the previous batch's hash.

Exit code 0 = pass; non-zero = fail. Prints a short summary.

Usage: python3 verify_apk.py path/to/app-release.apk
"""
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

REQUIRED_CERT_SHA256 = "1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953"
PREVIOUS_BATCH_HASH = "b9d85d6799d6e7c69a99eb9a714d9b050eeccf0aaddf1e198a62d4d04f64a208"

EXPECTED_VERSION_CODE = "3"
EXPECTED_VERSION_NAME = "1.0.2"
EXPECTED_PACKAGE = "app.reader"


def run(cmd: list[str]) -> tuple[int, str, str]:
    """Run [cmd], return (returncode, stdout, stderr) — combined output."""
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def find_tool(name: str, env_var: str | None = None) -> str | None:
    if env_var and os.environ.get(env_var):
        candidate = os.environ[env_var]
        if os.path.exists(candidate):
            return candidate
    return shutil.which(name)


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify_apk.py <apk>")
        return 2
    apk = Path(sys.argv[1])
    if not apk.exists() or apk.stat().st_size == 0:
        print(f"FAIL: APK not found or empty: {apk}")
        return 1

    failures = 0

    # --- 1. aapt2 dump badging ---
    aapt2 = find_tool("aapt2", "AAPT2_PATH") or shutil.which("aapt2")
    if not aapt2:
        print("FAIL: aapt2 not found on PATH (set AAPT2_PATH or install build-tools)")
        failures += 1
    else:
        rc, out, err = run([aapt2, "dump", "badging", str(apk)])
        first = (out or err).splitlines()[0] if (out or err) else ""
        print(f"[1] aapt2 dump badging (first line):")
        print(f"    {first}")
        if rc != 0:
            print("    FAIL: aapt2 returned non-zero")
            failures += 1
        else:
            ok = (
                f"versionCode='{EXPECTED_VERSION_CODE}'" in first
                and f"versionName='{EXPECTED_VERSION_NAME}'" in first
                and f"name='{EXPECTED_PACKAGE}'" in first
            )
            print(f"    -> versionCode/versionName/package match expected: {ok}")
            if not ok:
                failures += 1

    # --- 2. apksigner verify --print-certs ---
    apksigner = find_tool("apksigner", "APKSIGNER_PATH") or shutil.which("apksigner")
    if not apksigner:
        print("FAIL: apksigner not found on PATH")
        failures += 1
    else:
        rc, out, err = run([apksigner, "verify", "--print-certs", str(apk)])
        print(f"[2] apksigner verify --print-certs:")
        for line in (out + err).splitlines():
            if "SHA-256" in line or "Verifies" in line or "verified" in line:
                print(f"    {line.strip()}")
        if rc != 0:
            print(f"    FAIL: apksigner returned non-zero ({rc})")
            failures += 1
        elif REQUIRED_CERT_SHA256 not in (out + err).lower():
            print(f"    FAIL: required cert SHA-256 not found in output")
            failures += 1
        else:
            print(f"    -> cert SHA-256 matches required value")

    # --- 3. sha256sum ---
    h = hashlib.sha256()
    with apk.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    digest = h.hexdigest()
    print(f"[3] sha256sum {apk.name}:")
    print(f"    {digest}  {apk.name}")
    if digest.lower() == PREVIOUS_BATCH_HASH:
        print(f"    FAIL: hash matches previous batch's hash — APK must differ")
        failures += 1
    else:
        print(f"    -> differs from previous batch hash ({PREVIOUS_BATCH_HASH[:12]}…) ✓")

    # --- Summary ---
    print()
    if failures == 0:
        print(f"RESULT: PASS  ({apk.name})")
        return 0
    else:
        print(f"RESULT: FAIL  ({failures} check(s) failed)")
        return 1


if __name__ == "__main__":
    sys.exit(main())
