"""Exercise Android signing/inspection with a disposable TEST identity. Never installs or publishes."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_preview import read_version, validate_apk_contract
from release_contract import verify_metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    def tool(name):
        suffix = (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else ""
        return str((java if name == "keytool" else args.build_tools) / (name + suffix))
    def run(*cmd):
        return subprocess.check_output(list(map(str, cmd)), text=True, stderr=subprocess.STDOUT)
    name, code = read_version(ROOT)
    commit = run("git", "rev-parse", "HEAD").strip()
    with tempfile.TemporaryDirectory(prefix="banxuan-disposable-signing-") as temporary:
        directory = Path(temporary)
        key = directory / "fixture.jks"
        apk = directory / "fixture.apk"
        # Publicly known disposable password and DN; NOT a production keystore or signing identity.
        run(tool("keytool"), "-genkeypair", "-keystore", key, "-alias", "fixture",
            "-storepass", "fixture-only-password", "-keypass", "fixture-only-password",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=Banxuan Disposable Test Only", "-noprompt")
        run(tool("apksigner"), "sign", "--ks", key, "--ks-key-alias", "fixture",
            "--ks-pass", "pass:fixture-only-password", "--key-pass", "pass:fixture-only-password",
            "--out", apk, args.apk)
        signature = run(tool("apksigner"), "verify", "--verbose", "--print-certs", apk)
        certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature)[1]
        pin = directory / "fixture.sha256"
        pin.write_text(certificate)
        output = directory / "bundle"
        run(sys.executable, ROOT / "scripts/prepare_preview.py", "--apk", apk,
            "--out", output, "--commit", commit, "--tag", f"v{name}",
            "--certificate-file", pin, "--build-tools", args.build_tools)
        info = json.loads((output / "BUILD_INFO.json").read_text())
        import hashlib
        digest = hashlib.sha256((output / info["apk"]).read_bytes()).hexdigest()
        verify_metadata(info, f"v{name}", commit, name, code, digest, certificate)
        badge = run(tool("aapt"), "dump", "badging", apk)
        for bad_certificate, bad_badge in (("0" * 64, badge),
                                            (certificate, badge + "\napplication-debuggable\n")):
            try:
                validate_apk_contract(bad_badge, signature, name, code, bad_certificate)
            except ValueError:
                pass
            else:
                raise AssertionError("Invalid signing contract was accepted")
    print("PASS: disposable signing, real APK inspection/provenance, wrong signer/debug rejection; key deleted")
    print("NOT CLAIMED: production key, GitHub Environment, hosted tag release or Android data migration")


if __name__ == "__main__":
    main()
