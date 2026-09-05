# ReceiptBox corpus OCR+parser report

**Date:** 2026-09-05 (Asia/Jerusalem)  
**Corpus:** `testdata/corpus/` — **102** images (`manifest.json`)  
**Pipeline:** Tesseract 5 batch OCR → `ReceiptOcrParser` **v1.4-idr-thousands**  
**Artifacts:** `ocr/*.txt`, `results.jsonl`, `SOURCES.md`, this report

## Headline metrics (from this run only)

| Metric | Count | Rate |
|--------|------:|-----:|
| Images / OCR texts | 102 | 100% |
| OCR blank/tiny (<20 chars) | 3 | 2.9% |
| Parser crashes | 0 | 0.0% |
| Total amount present | 73 | 71.6% |
| Merchant non-empty | 100 | 98.0% |
| ≥1 line item | 58 | 56.9% |
| ≥1 discount | 11 | 10.8% |
| Mean confidence | 0.664 | n=102 |

### Currency distribution

| Currency | Count | % |
|----------|------:|--:|
| USD | 86 | 84.3% |
| IDR | 9 | 8.8% |
| ILS | 4 | 3.9% |
| EUR | 2 | 2.0% |
| GBP | 1 | 1.0% |

Many USD/foreign expected (SRD = US restaurants). CORD is Indonesian; IDR only when OCR exposes `Rp`/`IDR` or clear `.000` thousand groups.

### By source

| Source | n | total% | ≥1 item% | Notes |
|--------|--:|-------:|---------:|-------|
| srd | 55 | 81.8% | 72.7% | |
| cord | 45 | 60.0% | 37.8% | |
| local | 2 | 50.0% | 50.0% | |

## Top failure modes

1. **Sparse / failed OCR (CORD phone photos)** — dark/angled shots → tiny/garbled text → missing totals & items.
2. **Totals without line items** — `TOTAL`/`GRAND TOTAL` matched while item rows are unreadable or use `1 x 17.000` layouts the heuristic misses.
3. **Currency default USD on CORD** — most Indonesian receipts still land on USD; only a minority get IDR.
4. **Thousand-separator OCR noise** — spaces inside amounts (`9, 000`), mixed symbols; partially fixed for clean `46,000` / `17.000`.
5. **Garbage merchants** — high “non-empty” rate, but many values are punctuation/noise lines.
6. **Raw local Super-Pharm photo** — single-pass eng+heb PSM6 fails on the uncropped photo. Curated multi-pass OCR (side file) still hits ground truth — OCR pipeline gap more than parser.
7. **VAT-as-item** — uncommon in this corpus after existing VAT filters.

## Concrete examples

### GOOD SRD #1: `srd_000`
- currency=USD total=51.9 items=2 discounts=1 conf=0.8888889
- merchant='= GREEN FIELD'
- items sample: '2 Lunch|j 1 Coke'
- OCR preview: '_ — | » 4 = | 7 \\ = | = GREEN FIELD | 5305 E PACIFIC COAST HWY | Long Beach, CA 90804 | (S62) 597-0906 | : : Server: Francis Station: 3 | i Order #: 69923 att Dine In | _ Table: B11 Guests: e | | Coff'

### GOOD SRD #2: `srd_004`
- currency=USD total=15.03 items=2 discounts=0 conf=1.0
- merchant='S3OLDEN BOWL TERIYAKI'
- items sample: 'CHKN PLATE|AVOCADO'
- OCR preview: 'a | S3OLDEN BOWL TERIYAKI | sone GARFIELD AVE. | S ITH GATE, CA. 90280 | TEL: (882) g28-s999 | REG 05-18-2019 11:19 AM | Manéger1 MC #01 000141 | ORDER# oOoOO0141 | 1 #1 CHKN PLATE $9.39 | 1 + AVOCADO'

### GOOD SRD #3: `srd_005`
- currency=USD total=167.0 items=5 discounts=0 conf=0.8888889
- merchant="et | / 4 ASKA'S t"
- items sample: 'Conboy|J me Add ese fe|Manhattan|O me ihistle Pia Rye l0yr|Top Sirloin'
- OCR preview: "Mp, | = : | a ) | f 4 | et | / 4 ASKA'S t | Pat. San Diego CA 92109 i | ee ’ (858) 488-7311 j | Mam Server: Sue 08/15/2017 } | aly ts: 2 iooos | 5 th Draft Blackhouse i0 | | Ly Me “Conboy” 1602 Ribeye"

### IDR #1: `cord_002`
- currency=IDR total=28000.0 items=3 discounts=0 conf=0.7777778
- merchant='TOTAL TTS \\'
- items sample: '1 130 INN|SUN Ela|SUB'
- OCR preview: '1 130 INN 24,000 |  | SUN Ela 4.000 |  | SUB [UT 28,000 | TOTAL Sales 28,000 | TOTAL TTS \\ | cast 100,00. | “HANG | | 1G NO NO) an | '

### IDR #2: `cord_005`
- currency=IDR total=29.18 items=2 discounts=0 conf=0.7777778
- merchant='TRAD KY TOAST CARTE 28.182'
- items sample: 'TRAD KY TOAST CARTE|ITEMS'
- OCR preview: 'TRAD KY TOAST CARTE 28.182 | ITEMS 1.00 | SUBTTL 28.182 | PB-1 10% 2.818 | TOTAL 31.000 | CASH 31.000 | = | '

### BAD no-total #1: `cord_009`
- currency=USD total=None items=0 discounts=1 conf=0.44444445
- merchant='Bumbu Kaldu Ayam 1 span'
- items sample: ''
- OCR preview: 'a |  | Bumbu Kaldu Ayam 1 span |  | 36000 pee |  | Sub Total 36000 : : |  | Discount (0 %) 2D |  | Tunal 0000 1 |  | ji “. |  | Kembalian 14000 : |  | ey 2 | '

### BAD no-total #2: `cord_012`
- currency=USD total=None items=0 discounts=0 conf=0.33333334
- merchant='0571-1854 RiUS WALT'
- items sample: ''
- OCR preview: 'c | 0571-1854 RiUS WALT | i #120, on : , | 1002-9980 SHOPE i NG | CC. Vis3 | Tota! | '

### BAD no-total #3: `cord_013`
- currency=USD total=None items=0 discounts=0 conf=0.33333334
- merchant='a x iy OD I@UARNEYV v4'
- items sample: ''
- OCR preview: '- | eo) | e | Z | Aa | | | = | 3 | a x iy OD I@UARNEYV v4 | (eno. han | TN | 3 « | a % 4 | Z | '

### LOCAL raw tesseract: `local_oriented_up`
- currency=ILS total=35.9 items=2 discounts=1 conf=0.8888889
- merchant='Super-Pharm סופר \u200eOK\u200f בילו סנטר'
- items sample: '\u200e152.5/NICOTINELL MINT 4M\u200f 57 57ן|PURE 24 .0.1'
- OCR preview: 'סופר \u200eOK\u200f בילו סנטר | | חברת שגית לביא בע"מ | 3 קרית עקרו מתחם | 5 - 111970 0778880520 | ₪ | | חפ 514203975 , \u200ePOI\u200f מע"מ מס 657-2066985 | \u200epron +) ₪\u200f | "ץק | ושב\' מס קבלה 8661758 | . 00 7 46007 = מזיר'

### LOCAL raw tesseract: `local_superpharm_bilu_real`
- currency=ILS total=None items=0 discounts=0 conf=0.22222222
- merchant='- % \u200fו\u200e ₪'
- items sample: ''
- OCR preview: '~ | = oO = | - % \u200fו\u200e ₪ | — - am = & \u200fד\u200e | 2% 2 - | — — | = 4 \u200fיש כו\u200e SS \u200fהו ו\u200e oes | \u200fרז\u200e / | \u200fה]\u200e area UE \u200f=לל- | כ\u200e | = 2 = es ee pe \u200fו\u200e fa oe % <- = | 5 ₪ Sem a Bee = = | = | = | & 2 | = ₪ = Sea, ₪'


### Curated local Super-Pharm (side file, not in headline metrics)
`local_superpharm_bilu_real.curated.ocr.txt` (unit-test multi-pass dump): ILS, total=171.47, tax=26.16, 2 items, 1 discount, conf=1.0, merchant=`סופר-פארם בילו סנטר`.

## Fixes applied in this evaluation

1. **IDR currency** detection (`Rp`/`IDR`/`CASHIDR` + rupiah-like heuristics).
2. **Thousand-grouped money** (`46,000`, `17.000`, `1.234.567`) with guards so ILS/USD decimals (e.g. OCR `35.907`) are not inflated to `35907`.
3. Unit tests `indonesianReceipt_*`; `./gradlew testDebugUnitTest --tests ReceiptOcrParserTest` green.
4. `DebugSeed` named-args compile fix after `ParsedLineItem.unit` insertion.

## Recommended next fixes (impact order)

1. **OCR preprocess** — deskew, adaptive threshold, crop (largest win for CORD + raw Super-Pharm).
2. **IDR layouts** — spaced thousands, prefer TOTAL over TAX, Indonesian keywords (`Tunai`).
3. **Line items** — `QTY x PRICE TOTAL` and amount-on-next-line patterns.
4. **Merchant garbage filter** — reject `=`/`|`-heavy lines.
5. **Currency prior from lang pack** — `ind` OCR → bias IDR.
6. **Multi-pass tesseract** (PSM 4+6, lang variants) + merge in batch (already used for IL unit tests).

## Repro

```bash
bash testdata/corpus/scripts/batch_ocr.sh
cd testdata/corpus/harness && kotlinc StructuredModels.kt ReceiptOcrParser.kt CorpusHarness.kt -include-runtime -d corpus-harness.jar
java -jar corpus-harness.jar ../ocr ../results.jsonl
```

See `SOURCES.md` for licenses/URLs.
