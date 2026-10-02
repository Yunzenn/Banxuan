"""Package an inspected CI APK; never rebuild or re-sign it during publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def check_contents(names):
    required = {"AndroidManifest.xml", "classes.dex", "assets/licenses/concentus-LICENSE.txt"}
    if not required.issubset(names):
        raise ValueError("APK missing manifest, code or Concentus license")
    for name in names:
        lower = name.lower()
        if ("live2d" in lower or "cubism" in lower or lower.endswith(
            (".moc3", ".model3.json", ".keystore", ".jks", ".pem", ".key", ".mp4", ".zip")
        ) or (lower.startswith("assets/") and not lower.startswith("assets/licenses/"))):
            raise ValueError(f"Unapproved Preview payload: {name}")


def read_version(root):
    values = {}
    for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith(("VERSION_NAME=", "VERSION_CODE=")):
            key, value = line.split("=", 1)
            if key in values:
                raise ValueError("Duplicate version property")
            values[key] = value.strip()
    name = values["VERSION_NAME"]
    code = int(values["VERSION_CODE"])
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?", name) or code <= 0:
        raise ValueError("Invalid Preview version")
    return name, code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    version_name, version_code = read_version(Path(__file__).resolve().parent.parent)
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise ValueError("Expected exact build commit SHA")
    with zipfile.ZipFile(args.apk) as apk:
        if apk.testzip() is not None:
            raise ValueError("Corrupt APK")
        names = apk.namelist()
        check_contents(names)
    windows = os.name == "nt"
    def run(tool, *arguments):
        suffix = (".bat" if tool == "apksigner" else ".exe") if windows else ""
        return subprocess.check_output([str(args.build_tools / (tool + suffix)), *map(str, arguments)], text=True)
    badging = run("aapt", "dump", "badging", args.apk)
    for expected in ("name='com.aiwatch.probe'", f"versionName='{version_name}'", f"versionCode='{version_code}'", "sdkVersion:'28'"):
        if expected not in badging:
            raise ValueError(f"Unexpected APK identity/version: missing {expected}")
    signature = run("apksigner", "verify", "--verbose", "--print-certs", args.apk)
    run("zipalign", "-c", "4", args.apk)
    args.out.mkdir(parents=True, exist_ok=False)
    name = f"banxuan-{version_name}.apk"
    shutil.copyfile(args.apk, args.out / name)
    digest = hashlib.sha256((args.out / name).read_bytes()).hexdigest()
    (args.out / "SHA256SUMS.txt").write_text(f"{digest}  {name}\n", encoding="utf-8")
    (args.out / "APK_BADGING.txt").write_text(badging, encoding="utf-8")
    (args.out / "APK_SIGNATURE.txt").write_text(signature, encoding="utf-8")
    metadata = {
        "commit": args.commit, "apk": name, "sha256": digest, "minSdk": 28,
        "versionName": version_name, "versionCode": version_code,
        "nativeLibraries": [n for n in names if n.startswith("lib/")],
        "signing": "Debug certificate; not a stable release/update key",
        "scope": "Daylight and Adaptive Round 1; W0-W4 software evidence; no deployed memory authority",
        "notValidated": ["real backend voice", "real phone", "CD12Max", "full visual acceptance"],
    }
    (args.out / "BUILD_INFO.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(Path(__file__).resolve().parent.parent / "PREVIEW.md", args.out / "PREVIEW.md")
    shutil.copyfile(Path(__file__).resolve().parent.parent / "LICENSE", args.out / "LICENSE.txt")
    print(f"Preview inspected: {name} sha256={digest} commit={args.commit}")


if __name__ == "__main__":
    main()
