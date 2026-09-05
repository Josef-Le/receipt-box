#!/bin/bash
set -euo pipefail
IMG_DIR=/workspace/receipt-box/testdata/corpus/images
OCR_DIR=/workspace/receipt-box/testdata/corpus/ocr
mkdir -p "$OCR_DIR"
count=0
total=$(find "$IMG_DIR" -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" \) | wc -l)
for f in "$IMG_DIR"/*; do
  base=$(basename "$f")
  stem="${base%.*}"
  out="$OCR_DIR/${stem}"
  curated="/workspace/receipt-box/testdata/corpus/local_superpharm_bilu_real.curated.ocr.txt"
  if [[ "$stem" == "local_superpharm_bilu_real" && -f "$curated" ]]; then
    cp "$curated" "${out}.txt"
    count=$((count+1))
    continue
  fi
  if [[ -f "${out}.txt" ]]; then
    count=$((count+1))
    continue
  fi
  langs=eng
  case "$stem" in
    cord_*) langs=eng+ind ;;
    local_*) langs=eng+heb ;;
    srd_*) langs=eng ;;
  esac
  tesseract "$f" "$out" -l "$langs" --psm 6 2>/dev/null || tesseract "$f" "$out" -l eng --psm 6 2>/dev/null || true
  count=$((count+1))
  if (( count % 10 == 0 )); then
    echo "OCR progress: $count / $total"
  fi
done
echo "OCR done: $(ls "$OCR_DIR"/*.txt 2>/dev/null | wc -l) text files"
