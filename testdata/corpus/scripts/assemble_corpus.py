#!/usr/bin/env python3
"""Assemble ~100 varied open receipt images into corpus/images with source tags."""
import os, shutil, hashlib, json
from pathlib import Path

ROOT = Path("/workspace/receipt-box/testdata/corpus")
IMG = ROOT / "images"
if IMG.exists():
    shutil.rmtree(IMG)
IMG.mkdir(parents=True, exist_ok=True)
DL = ROOT / "downloads"

manifest = []

def add_from(src_dir, prefix, source, license_, limit, start=0):
    files = sorted(Path(src_dir).rglob("*"))
    files = [f for f in files if f.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}]
    files = files[start:start+limit]
    for i, f in enumerate(files):
        dest_name = f"{prefix}_{i:03d}{f.suffix.lower()}"
        dest = IMG / dest_name
        shutil.copy2(f, dest)
        h = hashlib.md5(dest.read_bytes()).hexdigest()
        manifest.append({
            "file": dest_name,
            "source": source,
            "license": license_,
            "md5": h,
            "orig": str(f.relative_to(DL) if f.is_relative_to(DL) else f),
        })

# ExpressExpense SRD — US restaurant receipts, MIT (55)
add_from(DL / "srd", "srd", "ExpressExpense SRD https://expressexpense.com/blog/free-receipt-images-ocr-machine-learning-dataset/", "MIT", 55)

# CORD test — Indonesian receipts, CC-BY-4.0 (45)
add_from(DL / "cord-test", "cord", "CORD (NAVER Clova) via Heliosoph/CORD HF https://huggingface.co/datasets/Heliosoph/CORD", "CC-BY-4.0", 45)

# Existing project testdata
extra = Path("/workspace/receipt-box/testdata")
for name in ["superpharm_bilu_real.jpg", "oriented_up.png"]:
    p = extra / name
    if p.exists():
        dest_name = f"local_{name}"
        dest = IMG / dest_name
        shutil.copy2(p, dest)
        manifest.append({
            "file": dest_name,
            "source": "local project testdata (user-captured / preprocess)",
            "license": "project-internal",
            "md5": hashlib.md5(dest.read_bytes()).hexdigest(),
            "orig": name,
        })

(ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2))
print(f"assembled {len(manifest)} images")
