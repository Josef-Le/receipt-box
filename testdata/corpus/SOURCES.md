# Corpus sources (royalty-free / open research)

Downloaded 2026-09-05 for ReceiptBox OCR+parser evaluation. No Google Images scraping of personal receipts.

## Included in `images/` (102 files)

| Prefix | Count | Dataset | License | Notes |
|--------|------:|---------|---------|-------|
| `srd_*` | 55 | ExpressExpense Sample Receipt Dataset (SRD) | MIT | US restaurant receipts. https://expressexpense.com/blog/free-receipt-images-ocr-machine-learning-dataset/ (zip md5 `c8eb0f2d286da5ab742e7a5b59f15147`) |
| `cord_*` | 45 | CORD (NAVER Clova) test split via Heliosoph/CORD mirror | CC-BY-4.0 | Indonesian receipts. Upstream: https://github.com/clovaai/cord · mirror: https://huggingface.co/datasets/Heliosoph/CORD (`cord-test-images.zip`) |
| `local_*` | 2 | Project testdata | project-internal | `superpharm_bilu_real.jpg` + `oriented_up.png` (Israeli Super-Pharm); for app regression, not redistributed as open data |

See `manifest.json` for per-file md5 + orig paths.

## Not used (friction / auth)

- **SROIE / ICDAR2019** full image tarball (Google Drive / competition registration). HF `jsdnrs/ICDAR2019-SROIE` is CC-BY-4.0 but image payload still points at Drive; skipped to avoid auth friction.
- **Kaggle** receipt sets (account required).
- **Zenodo** ExpressExpense-derived labeled set (CC-BY-4.0) — download blocked in this environment; SRD MIT zip already covers the same photo family.

## OCR stack

- Tesseract 5 (`tesseract-ocr`) with langs: `eng`, `heb`, `ara`, `ind`, `deu`, `fra` (+ `osd`)
- Per-image languages: SRD → `eng`; CORD → `eng+ind`; local IL → `eng+heb`; PSM 6
- ML Kit not used (Android-only)

## Side artifact

- `local_superpharm_bilu_real.curated.ocr.txt` — multi-pass curated OCR from unit-test resources (not from single-shot tesseract). Documented for comparison; **main `results.jsonl` uses raw tesseract** for locals.
