"""Fail-closed tag and signed APK provenance checks; no credentials or network writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from prepare_preview import read_version, validate_apk_contract


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def check_tag(tag, name, code, commit, head, tag_commit, previous_codes):
    if tag != f"v{name}":
        raise ValueError("Tag must equal vVERSION_NAME")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or commit != head or commit != tag_commit:
        raise ValueError("Tag, checkout and event commit must be identical")
    if any(code <= previous for previous in previous_codes):
        raise ValueError("VERSION_CODE must exceed all earlier Preview tags")


def pinned_certificate(path):
    value = path.read_text(encoding="utf-8").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Release signing certificate is not configured")
    return value


def verify_metadata(info, tag, commit, name, code, digest, signer):
    expected = {"tag": tag, "commit": commit, "versionName": name, "versionCode": code,
                "apk": f"banxuan-{name}.apk", "sha256": digest, "minSdk": 28,
                "certificateSha256": signer, "signing": "Pinned release certificate"}
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError(f"BUILD_INFO mismatch: {key}")


def verify_remote_release(remote, tag, bundle):
    if remote.get("tag_name") != tag or not remote.get("draft") or not remote.get("prerelease"):
        raise ValueError("Expected the new Preview draft")
    local = {p.name: p for p in bundle.iterdir() if p.is_file()}
    assets = remote.get("assets", [])
    if len(assets) != len(local) or {a["name"] for a in assets} != set(local):
        raise ValueError("Remote asset inventory mismatch")
    for asset in assets:
        data = local[asset["name"]].read_bytes()
        if (asset.get("state") != "uploaded" or asset.get("size") != len(data) or
                asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest()):
            raise ValueError("Remote asset digest/size/state mismatch")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--remote-release", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    name, code = read_version(root)
    # No arbitrary tag text is interpolated into a command or shell.
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?", args.tag):
        raise ValueError("Invalid Preview tag")
    previous_codes = []
    for tag in git("tag", "--list", "v*-preview*").splitlines():
        if tag == args.tag:
            continue
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?", tag):
            continue
        properties = git("show", f"refs/tags/{tag}:gradle.properties")
        match = re.search(r"^VERSION_CODE=(\d+)$", properties, re.M)
        if not match:
            # Legacy 0.4.0 was published before the single version source existed.
            legacy = git("show", f"refs/tags/{tag}:app/build.gradle.kts")
            match = re.search(r"\bversionCode\s*=\s*(\d+)", legacy)
        if not match:
            raise ValueError(f"Cannot audit previous versionCode for {tag}")
        previous_codes.append(int(match[1]))
    check_tag(args.tag, name, code, args.commit, git("rev-parse", "HEAD"),
              git("rev-parse", f"refs/tags/{args.tag}^{{commit}}"), previous_codes)
    subprocess.run(["git", "merge-base", "--is-ancestor", args.commit, "origin/main"], check=True)
    certificate = pinned_certificate(root / "release/signing-certificate.sha256")
    if args.bundle:
        if args.build_tools is None:
            raise ValueError("Bundle validation requires Android build tools")
        info = json.loads((args.bundle / "BUILD_INFO.json").read_text(encoding="utf-8"))
        # Construct the filename from the reviewed version, not untrusted metadata.
        apk_name = f"banxuan-{name}.apk"
        apk = args.bundle / apk_name
        def tool(tool_name, *argv):
            suffix = (".bat" if tool_name == "apksigner" else ".exe") if os.name == "nt" else ""
            return subprocess.check_output([str(args.build_tools / (tool_name + suffix)), *map(str, argv)], text=True)
        badging = tool("aapt", "dump", "badging", apk)
        signature = tool("apksigner", "verify", "--verbose", "--print-certs", apk)
        tool("zipalign", "-c", "4", apk)
        signer = validate_apk_contract(badging, signature, name, code, certificate)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        verify_metadata(info, args.tag, args.commit, name, code, digest, signer)
        for filename, actual in (("APK_BADGING.txt", badging), ("APK_SIGNATURE.txt", signature),
                                 ("SHA256SUMS.txt", f"{digest}  {apk_name}\n")):
            if (args.bundle / filename).read_text(encoding="utf-8") != actual:
                raise ValueError(f"Provenance sidecar mismatch: {filename}")
        if args.remote_release:
            verify_remote_release(json.loads(args.remote_release.read_text()), args.tag, args.bundle)
    print("Release contract verified")


if __name__ == "__main__":
    main()
