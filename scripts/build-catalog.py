"""只解析用户提供的 SQL 文本，生成离线条码资料；不执行任何 SQL。"""

import argparse
import collections
import gzip
import hashlib
import html
import json
import re
from pathlib import Path


def parse_values(text):
    """读取单行 MySQL 导出中的字符串与 NULL，不使用 eval 或数据库。"""
    values = []
    index = 0
    escapes = {"0": "\0", "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a"}
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            raise ValueError("字段缺失")
        if text[index] == "'":
            index += 1
            chars = []
            while index < len(text):
                char = text[index]
                index += 1
                if char == "\\":
                    if index >= len(text):
                        raise ValueError("转义不完整")
                    escaped = text[index]
                    chars.append(escapes.get(escaped, escaped))
                    index += 1
                elif char == "'":
                    if index < len(text) and text[index] == "'":
                        chars.append("'")
                        index += 1
                    else:
                        break
                else:
                    chars.append(char)
            else:
                raise ValueError("字符串未闭合")
            values.append("".join(chars))
        else:
            end = text.find(",", index)
            if end < 0:
                end = len(text)
            token = text[index:end].strip()
            if token.lower() == "null":
                values.append(None)
            elif re.fullmatch(r"[+-]?\d+(?:\.\d+)?", token):
                values.append(token)
            else:
                raise ValueError("出现不支持的字段")
            index = end
        while index < len(text) and text[index].isspace():
            index += 1
        if index == len(text):
            return values
        if text[index] != ",":
            raise ValueError("字段分隔错误")
        index += 1
    raise ValueError("记录不完整")


def valid_gtin(code):
    if len(code) not in (8, 12, 13, 14) or not re.fullmatch(r"[0-9]+", code):
        return False
    total = sum(int(char) * (3 if index % 2 == 0 else 1)
                for index, char in enumerate(reversed(code[:-1])))
    return (10 - total % 10) % 10 == int(code[-1])


def clean(value):
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def build(source, output):
    rows = 0
    rejected = collections.Counter()
    categories = collections.Counter()
    records = collections.defaultdict(set)
    prefix = "INSERT INTO `medicine_info` VALUES ("
    with source.open(encoding="utf-8-sig") as stream:
        for number, line in enumerate(stream, 1):
            if not line.startswith(prefix):
                continue
            if not line.rstrip().endswith(");"):
                raise ValueError(f"第 {number} 行不是完整的单行记录")
            fields = parse_values(line.rstrip()[len(prefix):-2])
            if len(fields) != 16:
                raise ValueError(f"第 {number} 行字段数量错误")
            rows += 1
            categories[clean(fields[1])] += 1
            code = clean(fields[4])
            if not valid_gtin(code):
                rejected["invalidBarcode"] += 1
                continue
            name, spec, unit, manufacturer, approval = [clean(fields[i]) for i in (2, 5, 6, 14, 13)]
            if not name or len(name) > 80 or len(spec) > 120 or len(unit) > 8:
                rejected["unsupportedFields"] += 1
                continue
            if len(manufacturer) > 200 or len(approval) > 100:
                rejected["unsupportedFields"] += 1
                continue
            # 仅保留核对药盒身份的基本字段，不导入用法、剂量或说明书。
            records[code.zfill(14)].add((name, spec, unit or "盒", manufacturer, approval))

    output.mkdir(parents=True, exist_ok=True)
    buckets = collections.defaultdict(dict)
    for code, candidates in sorted(records.items()):
        buckets[code[-2:]][code] = [list(candidate) for candidate in sorted(candidates)]
    uncompressed = compressed = 0
    max_candidates = 0
    for suffix in (f"{i:02d}" for i in range(100)):
        data = json.dumps(buckets.get(suffix, {}), ensure_ascii=False, separators=(",", ":")).encode()
        archive = gzip.compress(data, compresslevel=9, mtime=0)
        (output / f"{suffix}.json.gz").write_bytes(archive)
        uncompressed += len(data)
        compressed += len(archive)
    for candidates in records.values():
        max_candidates = max(max_candidates, len(candidates))
    manifest = {
        "formatVersion": 1,
        "source": "https://github.com/EricLiuCN/barcode",
        "sourceExportedAt": "2023-09-07",
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "sourceBytes": source.stat().st_size,
        "sourceRows": rows,
        "sourceCategories": dict(sorted(categories.items())),
        "barcodeCount": len(records),
        "candidateCount": sum(map(len, records.values())),
        "ambiguousBarcodeCount": sum(len(candidates) > 1 for candidates in records.values()),
        "maxCandidates": max_candidates,
        "rejected": dict(rejected),
        "uncompressedBytes": uncompressed,
        "compressedBytes": compressed,
        "fields": ["name", "specification", "packageUnit", "manufacturer", "approval"],
        "acknowledgement": "感谢 EricLiuCN/barcode 项目维护者与贡献者收集、整理并分享条码资料。",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.source, args.output)
