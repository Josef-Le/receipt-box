# OCR → Parse corpus eval

**Date:** 2026-09-05 (Asia/Jerusalem)  
**Corpus:** `testdata/corpus/` — **102** images (manifest.json)  
**Pipeline:** Tesseract batch OCR → `ReceiptOcrParser` v1.3-il-real  
**Artifacts:** `ocr/*.txt`, `results.jsonl`, this report

## Headline metrics

| Metric | Count | Rate |
|--------|------:|-----:|
| Images / OCR texts | 102 | 100% |
| OCR blank / empty | 2 | 2.0% |
| Parser crashes | 0 | 0.0% |
| Total amount present | 74 | 72.5% |
| Merchant non-empty | 100 | 98.0% |
| ≥1 line item | 59 | 57.8% |
| ≥1 discount | 13 | 12.7% |
| Tax present | 38 | 37.3% |
| Subtotal present | 40 | 39.2% |
| Mean confidence (non-null) | 0.672 | n=102 |
| Mean line-item count | 1.97 | — |

## By source

| Source | n | total% | ≥1 item% |
|--------|--:|-------:|---------:|
| CORD | 45 | 60.0% | 37.8% |
| SRD | 55 | 81.8% | 72.7% |
| local | 2 | 100.0% | 100.0% |
## Currency distribution

| Currency | Count |
|----------|------:|
| USD | 86 |
| IDR | 9 |
| ILS | 4 |
| EUR | 2 |
| GBP | 1 |

## Local Super-Pharm (ground-truth check)

`local_superpharm_bilu_real` OCR is the proven multi-pass fixture (`local_superpharm_bilu_real.curated.ocr.txt`).
Raw single-pass `eng+heb` on the unrotated photo is near-unreadable; production uses orientation search + merge.

| Field | Parsed |
|-------|--------|
| currency | ILS |
| total | 171.47 |
| tax | 26.16 |
| subtotal | 145.31 |
| line_items | 2 |
| discounts | 1 |
| merchant | סופר-פארם בילו סנטר |
| company | | חברת שגית לביא בע"מ |
| confidence | 1.0 |
| item sample | NICOTINELL MINT 4M|PURE 24 .0.1 |

## Honest gaps

- OCR quality dominates public CORD/SRD slices (thermal/blur).
- Line-item recall 58% overall — presence metrics, not exact-match gold labels (except Super-Pharm).
- Merchant often captures noise when store name is unreadable.
- Discounts rare in OCR (13/102).
- Heuristic parser (not ML). Single-pass Tesseract fails on rotated Hebrew; curated/multi-pass required.

## Reproduce

```bash
bash testdata/corpus/scripts/batch_ocr.sh
kotlinc -d /tmp/ch testdata/corpus/harness/StructuredModels.kt \
  testdata/corpus/harness/ReceiptOcrParser.kt testdata/corpus/harness/CorpusHarness.kt
kotlin -cp /tmp/ch CorpusHarnessKt testdata/corpus/ocr testdata/corpus/results.jsonl
```
