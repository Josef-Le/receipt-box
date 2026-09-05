# ReceiptBox

Snap receipts → on-device OCR → **structured relational expense data** → analytics + CSV/JSON/PDF export for accountants.

**applicationId:** `com.receiptbox.app`  
**Monetization:** one-time Play product `receiptbox_pro` ($9.99)

## Features

- CameraX capture + gallery picker
- ML Kit Text Recognition (Latin) on-device
- Heuristic structured parser → Company, Store, Receipt, LineItems, Discounts, Payments, Product catalog, Raw OCR audit
- Jetpack Compose + Material 3 UI (onboarding, home, capture/edit, detail, analytics, export, paywall, settings)
- Room database with FKs + indices
- Free: max **15** receipts + watermarked exports + basic list
- Pro: unlimited receipts, full analytics, clean CSV/JSON relational dump
- DEBUG unlock toggle in Settings (debug builds)

## Schema (Room)

| Entity | Purpose |
|--------|---------|
| `Company` | Legal name, brand, tax id |
| `Store` | Branch/address/phone → Company |
| `Receipt` | Header: datetime, number, cashier, register, currency, subtotal/tax/total, status (`PURCHASE`/`REFUND`/`VOID`), category, confidence, optional link to original receipt |
| `Product` | Normalized catalog (barcode / SKU / name+store) |
| `LineItem` | Product rows on a receipt |
| `Discount` | Header or line-level amount/percent/code |
| `Payment` | Tender method, amount, last4/auth, change |
| `RawOcrText` | Full OCR text for audit |

Refunds link to an original purchase when receipt numbers match.

## Analytics (Pro)

Filters: date range, store, company, category, payment method, refunds-only, period grain (day/week/month).

Aggregations: total spend, avg basket, discount rate, spend by store, top products/SKUs, payment mix, period bars, top products at top store.

## Build

```bash
# JDK 17+, Android SDK 35
export JAVA_HOME=... ANDROID_HOME=...
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (applicationId suffix `.debug`).

## How OCR works (offline)

1. Image → ML Kit on-device text recognition (no network).
2. `ReceiptOcrParser` applies regex/heuristics for merchant, tax id, phone, dates, labeled totals, line items (qty × price), discounts, payment tenders.
3. User reviews/edits before save.
4. `ReceiptRepository.saveFromParse` upserts company/store/products and persists related rows + raw text.

Accuracy is best-effort; always editable.

## Privacy

Photos, OCR text, and structured data stay on the device. No accounts, no backend, no ads in v1.

## Play Console checklist (`receiptbox_pro`)

1. Create app with applicationId `com.receiptbox.app`.
2. Monetize → one-time product ID **`receiptbox_pro`**, price **$9.99** (or local equivalent).
3. Activate product; add license testers.
4. Upload AAB signed with your upload key; enable Play Billing permission (already in manifest).
5. Internal testing track → verify purchase + restore.
6. Privacy policy: state on-device processing; no cloud sync of receipts.

## Project layout

```
app/src/main/java/com/receiptbox/app/
  data/       Entities, DAOs, Room DB, repositories
  ocr/        ML Kit helper + structured parser
  export/     CSV / JSON / PDF writers
  billing/    Play Billing 7.x
  ui/         Compose screens (home, capture, detail, analytics, export, …)
```

## Out of scope (v1)

Cloud sync, bank connect, live FX rates, team sharing, AdMob.
