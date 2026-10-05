"""核对同次构建产物，仅在校验通过后用既有证书签名。"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

FINGERPRINT = "13a7761ed3ba2bdfd4572bff54783864fa3451549df62a5aee1477e591680276"
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def is_formal_version(version):
    """正式版使用规范的三段数字；从 1 开始，补丁位到 9 后进位。"""
    return isinstance(version, str) and re.fullmatch(
        r"[1-9][0-9]*\.(?:0|[1-9][0-9]*)\.[0-9]", version
    ) is not None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def tool(arguments):
    # 工具错误不回显环境变量、认证或完整进程输出。
    environment = dict(os.environ)
    environment.pop("MEDICINE_RELEASE_KEYSTORE_BASE64", None)
    if arguments[1] != "sign":
        environment.pop("MEDICINE_RELEASE_STORE_PASSWORD", None)
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True, text=True, encoding="utf-8", env=environment)
    require(result.returncode == 0, f"Android 工具执行失败：{Path(arguments[0]).name}")
    return result.stdout


def validate(input_dir, build_tools):
    metadata_file = input_dir / "output-metadata.json"
    require(metadata_file.is_file() and not metadata_file.is_symlink(), "构建元数据文件无效")
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    require(isinstance(metadata, dict) and isinstance(metadata.get("elements"), list) and all(isinstance(item, dict) for item in metadata["elements"]), "构建元数据格式无效")
    require(metadata.get("applicationId") == "app.medicinecabinet", "构建元数据包名不符")
    selected, versions = {}, set()
    for abi, filters in (("arm64-v8a", [{"filterType": "ABI", "value": "arm64-v8a"}]), ("universal", [])):
        elements = [item for item in metadata.get("elements", []) if item.get("filters") == filters]
        require(len(elements) == 1, f"构建元数据缺少唯一的 {abi} 包")
        element = elements[0]
        name = element.get("outputFile")
        require(name in {f"app-{abi}-release.apk", f"app-{abi}-release-unsigned.apk"}, "APK 文件名不在白名单")
        apk = input_dir / name
        require(apk.is_file() and not apk.is_symlink() and apk.stat().st_size > 0, "APK 文件不存在或为空")
        badging = tool([build_tools / "aapt", "dump", "badging", apk])
        package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
        require(package is not None and package[1] == "app.medicinecabinet", "APK 真实包名不符")
        require(package[2] == str(element.get("versionCode")) and package[3] == element.get("versionName"), "APK 版本与元数据不符")
        require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", package[3]) is not None, "版本号格式无效")
        native = re.search(r"^native-code: (.+)$", badging, re.M)
        require(native is not None and set(re.findall(r"'([^']+)'", native[1])) == ({abi} if abi != "universal" else ABIS), "APK 架构不符")
        require("application-debuggable" not in badging, "发行 APK 不能开启调试")
        versions.add((package[2], package[3]))
        selected[abi] = apk
    require(len(versions) == 1, "两种 APK 的版本不一致")
    require({item.name for item in input_dir.iterdir()} == {"output-metadata.json", *[p.name for p in selected.values()]}, "输入目录含非白名单文件")
    version_code, version = versions.pop()
    ref = os.environ.get("GITHUB_REF", "")
    if ref.startswith("refs/tags/"):
        require(re.fullmatch(r"refs/tags/v[0-9]+\.[0-9]+\.[0-9]+", ref) is not None and ref == f"refs/tags/v{version}", "标签必须等于 APK 版本")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version={version}\n")
    return selected, version, version_code


def sign(input_dir, build_tools, output_dir):
    selected, version, version_code = validate(input_dir, build_tools)
    encoded = os.environ.get("MEDICINE_RELEASE_KEYSTORE_BASE64", "")
    require(encoded and os.environ.get("MEDICINE_RELEASE_STORE_PASSWORD"), "尚未配置发行签名 Secrets")
    try:
        key_data = base64.b64decode(encoded, validate=True)
    except (ValueError, UnicodeError):
        raise ValueError("发行证书 Base64 格式无效") from None
    require(key_data, "发行证书为空")
    commit = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "缺少有效的构建提交编号")
    output_dir.mkdir(parents=True, exist_ok=True)
    checksums = []
    # 私钥只落在 Runner 临时目录；成功、失败均由上下文自动清理。
    with tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP")) as temporary:
        keystore = Path(temporary) / "release.p12"
        keystore.write_bytes(key_data)
        keystore.chmod(0o600)
        for abi, apk in selected.items():
            output = output_dir / f"medicine-cabinet-{version}-{abi}.apk"
            tool([build_tools / "apksigner", "sign", "--ks", keystore, "--ks-type", "PKCS12", "--ks-key-alias", "medicinecabinet", "--ks-pass", "env:MEDICINE_RELEASE_STORE_PASSWORD", "--key-pass", "env:MEDICINE_RELEASE_STORE_PASSWORD", "--v4-signing-enabled", "false", "--out", output, apk])
            verified = tool([build_tools / "apksigner", "verify", "--verbose", "--print-certs", output])
            fingerprints = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", verified, re.M)
            require([item.lower() for item in fingerprints] == [FINGERPRINT], "签名证书指纹不符")
            require("Verified using v2 scheme (APK Signature Scheme v2): true" in verified, "APK 未通过 v2 签名验证")
            checksums.append(f"{hashlib.sha256(output.read_bytes()).hexdigest()}  {output.name}\n")
    (output_dir / "SHA256SUMS.txt").write_text("".join(checksums), encoding="utf-8")
    (output_dir / "build-info.json").write_text(json.dumps({"versionName": version, "versionCode": int(version_code), "commit": commit, "certificateSha256": FINGERPRINT}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "sign"))
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            validate(args.input_dir, args.build_tools)
        else:
            require(args.output_dir is not None, "签名必须指定输出目录")
            sign(args.input_dir, args.build_tools, args.output_dir)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"安装包处理失败：{error}\n")


if __name__ == "__main__":
    main()
