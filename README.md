# ReceiptBox

Snap receipts → **multilingual on-device OCR** → structured relational expense data → analytics + CSV/JSON/PDF export for accountants.

**applicationId:** `com.receiptbox.app`  
**Version:** 0.1.3  
**Monetization:** one-time Play product `receiptbox_pro` ($9.99)

## Features

- CameraX capture + gallery picker
- **Multilingual OCR** (offline):
  - Image preprocess (grayscale / contrast) + orientation search
  - **ML Kit** Latin + Chinese + Japanese + Korean + Devanagari (merged)
  - **Tesseract 5** (`tesseract4android`) with `tessdata_fast` packs
  - Default bundled packs (~28 MB): `eng, heb, ara, rus, deu, fra, spa, por, ita, tur, pol`
  - Settings → download more languages from tessdata_fast; packs stored under app private files
- Heuristic structured parser → Company, Store, Receipt, LineItems, Discounts, Payments, Product catalog, Raw OCR audit
- Jetpack Compose + Material 3 UI
- Room database with FKs + indices
- Free: max **15** receipts + watermarked exports + basic list
- Pro: unlimited receipts, full analytics, clean CSV/JSON, **IL accountant CSV** (ח.פ. / VAT / lines / discounts)
- Home **global search** (merchant / company / tax id / receipt # / product / barcode / notes)
- Analytics: spend by store/product/period + **price history by barcode**
- DEBUG unlock toggle in Settings (debug builds)

## Proof (Super-Pharm Bilu)

Unit test `superPharmBiluRealOcrFile_parsesGroundTruth` loads real Tesseract `heb+eng` OCR from
`testdata/superpharm_bilu_real.ocr.txt` (photo: `testdata/superpharm_bilu_real.jpg`, upright = rotate 270°)
and asserts ground truth: 2 items (NICOTINELL 152.57, Lily PURE 35.90), coupon −17, total 171.47 ILS,
VAT 26.16, receipt 8661758, ח.פ. 514203975.

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Schema (Room)

| Entity | Purpose |
|--------|---------|
| `Company` | Legal name, brand, tax id (ח.פ.) |
| `Store` | Branch/address/phone → Company |
| `Receipt` | Header totals / status / confidence |
| `Product` | Normalized catalog (barcode / SKU) |
| `LineItem` | Product rows |
| `Discount` | Header or line-level |
| `Payment` | Tender method |
| `RawOcrText` | Full OCR audit |

## Build

```bash
# JDK 17+, Android SDK 35
export JAVA_HOME=... ANDROID_HOME=...
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (applicationId suffix `.debug`, versionName `0.1.3-debug`).

### Adding more OCR languages

1. In-app: **Settings → OCR language packs → Download**
2. Or drop `xx.traineddata` from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) into `app/src/main/assets/tessdata/` and rebuild

### Honest OCR limits

- Thermal / blurry / extreme-angle photos still fail; user can edit before save
- Multi-engine merge improves recall but can duplicate noisy lines — parser dedupes
- Bundled tessdata adds ~28 MB; ML Kit script models add more native weight
- Hebrew+English pharmacy receipts work best upright; the app tries 0/90/270/180

## Privacy

Photos, OCR text, and structured data stay on the device. Language-pack downloads hit GitHub raw only when the user installs extras. No accounts, no backend, no ads in v1.

## Project layout

```
app/src/main/java/com/receiptbox/app/
  data/       Entities, DAOs, Room DB, repositories
  ocr/        OcrHelper (ML Kit + Tesseract), TessLanguagePackManager, parser
  export/     CSV / IL accountant CSV / JSON / PDF
  billing/    Play Billing 7.x
  ui/         Compose screens
```
