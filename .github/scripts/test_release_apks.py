"""使用模拟 Android 工具与虚构认证，验证发行边界。"""
import base64
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import release_apks as release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        self.metadata = {"applicationId": "app.medicinecabinet", "elements": []}
        for abi in ("arm64-v8a", "universal"):
            name = f"app-{abi}-release-unsigned.apk"
            (self.inputs / name).write_bytes(b"mock-apk")
            self.metadata["elements"].append({"outputFile": name, "versionName": "0.1.11", "versionCode": 12, "filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}]})
        self.save_metadata()
        self.package, self.version, self.extra, self.bad_abi = "app.medicinecabinet", "0.1.11", "", False
        self.fingerprint, self.v2 = release.FINGERPRINT, "true"
        self.environment = {"GITHUB_REF": "refs/heads/main", "GITHUB_SHA": "a" * 40, "RUNNER_TEMP": str(self.root), "MEDICINE_RELEASE_KEYSTORE_BASE64": base64.b64encode(b"fictional-keystore").decode(), "MEDICINE_RELEASE_STORE_PASSWORD": "fictional-password"}
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, self.environment, clear=True).start()
        self.mock_tool = patch.object(release, "subprocess").start()
        self.mock_tool.run.side_effect = self.run_tool

    def save_metadata(self):
        (self.inputs / "output-metadata.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def run_tool(self, args, **kwargs):
        self.assertNotIn("MEDICINE_RELEASE_KEYSTORE_BASE64", kwargs["env"])
        if args[1] != "sign":
            self.assertNotIn("MEDICINE_RELEASE_STORE_PASSWORD", kwargs["env"])
        if args[1] == "dump":
            abis = ["x86"] if self.bad_abi else (sorted(release.ABIS) if "universal" in args[-1] else ["arm64-v8a"])
            text = f"package: name='{self.package}' versionCode='12' versionName='{self.version}'\nnative-code: " + " ".join(f"'{abi}'" for abi in abis) + "\n" + self.extra
        elif args[1] == "sign":
            self.assertNotIn("fictional-password", args)
            Path(args[args.index("--out") + 1]).write_bytes(b"signed-mock")
            text = ""
        else:
            text = f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\nVerified using v2 scheme (APK Signature Scheme v2): {self.v2}\n"
        return type("Result", (), {"returncode": 0, "stdout": text})()

    def test_valid_main_and_matching_tag(self):
        for ref in ("refs/heads/main", "refs/tags/v0.1.11"):
            os.environ["GITHUB_REF"] = ref
            self.assertEqual(release.validate(self.inputs, self.root)[1], "0.1.11")

    def test_formal_version_boundary(self):
        for version in ("1.0.0", "1.0.9", "1.1.0", "1.12.9", "2.0.0"):
            with self.subTest(version=version):
                self.assertTrue(release.is_formal_version(version))
        for version in ("0.1.11", "1.0.10", "01.0.0", "1.00.0", "1.0.01", "bad", "v1.0.0", "1.0.0\n", None):
            with self.subTest(version=version):
                self.assertFalse(release.is_formal_version(version))

    def test_reject_invalid_tags(self):
        for tag in ("v0.1.12", "v0.1.11-beta", "v0.1.11\nmalicious", "v０.１.１１"):
            os.environ["GITHUB_REF"] = "refs/tags/" + tag
            with self.assertRaises(ValueError):
                release.validate(self.inputs, self.root)

    def test_reject_bad_apk_identity(self):
        for field, value in (("package", "other.app"), ("version", "0.1.12"), ("extra", "application-debuggable"), ("bad_abi", True)):
            original = getattr(self, field)
            setattr(self, field, value)
            with self.assertRaises(ValueError):
                release.validate(self.inputs, self.root)
            setattr(self, field, original)

    def test_reject_bad_metadata_and_extra_file(self):
        self.metadata["elements"][1]["versionName"] = "0.1.12"
        self.save_metadata()
        with self.assertRaises(ValueError):
            release.validate(self.inputs, self.root)
        self.metadata["elements"][1]["versionName"] = "0.1.11"
        self.save_metadata()
        (self.inputs / "extra.txt").write_text("unexpected")
        with self.assertRaises(ValueError):
            release.validate(self.inputs, self.root)

    def test_sign_and_reject_wrong_certificate(self):
        outputs = self.root / "outputs"
        release.sign(self.inputs, self.root, outputs)
        self.assertTrue((outputs / "SHA256SUMS.txt").is_file())
        self.assertEqual(json.loads((outputs / "build-info.json").read_text())["commit"], "a" * 40)
        self.assertFalse(list(self.root.glob("*/release.p12")))
        for field, value in (("fingerprint", "b" * 64), ("v2", "false")):
            original = getattr(self, field)
            setattr(self, field, value)
            with self.assertRaises(ValueError):
                release.sign(self.inputs, self.root, outputs)
            self.assertFalse(list(self.root.glob("*/release.p12")))
            setattr(self, field, original)

    def test_missing_secrets_and_invalid_base64(self):
        for secret, value in (("MEDICINE_RELEASE_STORE_PASSWORD", ""), ("MEDICINE_RELEASE_KEYSTORE_BASE64", ""), ("MEDICINE_RELEASE_KEYSTORE_BASE64", "%%%")):
            original = os.environ[secret]
            os.environ[secret] = value
            with self.assertRaises(ValueError):
                release.sign(self.inputs, self.root, self.root / "outputs")
            os.environ[secret] = original


if __name__ == "__main__":
    unittest.main()
