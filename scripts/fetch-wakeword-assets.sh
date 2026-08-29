#!/usr/bin/env bash
# Fetches the binaries the wake-word engine needs. They are deliberately not
# committed - together they are ~63MB.
#
#   ./scripts/fetch-wakeword-assets.sh
#
# Downloads:
#   app/libs/sherpa-onnx-<VERSION>.aar          the engine (native + Kotlin API)
#   app/src/main/assets/<MODEL>/                the English KWS model
#
# Requires: curl, tar (with bzip2 support - Windows 10+ bsdtar is fine).
set -euo pipefail

SHERPA_VERSION="1.13.6"
MODEL="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$REPO_ROOT/app/libs"
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/$MODEL"

mkdir -p "$LIBS_DIR" "$ASSETS_DIR"

# --- engine ----------------------------------------------------------------
AAR="$LIBS_DIR/sherpa-onnx-$SHERPA_VERSION.aar"
if [ -f "$AAR" ]; then
  echo "aar already present, skipping"
else
  echo "downloading sherpa-onnx $SHERPA_VERSION (~49MB)..."
  curl -sSL -o "$AAR" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
fi

# --- model -----------------------------------------------------------------
if [ -f "$ASSETS_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.onnx" ]; then
  echo "model already present, skipping"
else
  echo "downloading KWS model (~18MB)..."
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  curl -sSL -o "$TMP/kws.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL.tar.bz2"
  tar -xjf "$TMP/kws.tar.bz2" -C "$TMP"

  # Only what is needed at runtime. The int8 variants are ~9MB smaller and
  # cheaper to run; swap them in here and in SherpaWakeWordDetector to trade a
  # little accuracy for battery.
  for f in encoder-epoch-12-avg-2-chunk-16-left-64.onnx \
           decoder-epoch-12-avg-2-chunk-16-left-64.onnx \
           joiner-epoch-12-avg-2-chunk-16-left-64.onnx \
           tokens.txt; do
    cp "$TMP/$MODEL/$f" "$ASSETS_DIR/$f"
  done
fi

# --- wake phrases ----------------------------------------------------------
# The model matches BPE token sequences, not plain text. \xe2\x96\x81 is the
# SentencePiece word-boundary marker U+2581 ("_"), NOT an underscore. Do not
# hand-edit these into plain text - see README.md, "Changing the wake word".
#
# Every word below is a single whole-word token in the model's vocabulary, so
# none of these phrases contains a lone character. That is what the model
# matches reliably - "HEY BUDDY" shredded into "HE Y BU D D Y" and barely ever
# triggered. See README.md, "Picking a phrase that actually works".
#
# Any of these three triggers the assistant. logcat names whichever matched,
# and the notification lists them all (read from this file, not hardcoded).
#
# Suffixes are per-phrase and override the Kotlin config, so sensitivity can be
# retuned without recompiling:
#   :2.0   boosting score     - higher triggers more easily
#   #0.15  trigger threshold  - lower triggers more easily
#   @NAME  display name, shown in logcat and the notification
{
  printf '\xe2\x96\x81MAKE \xe2\x96\x81IT \xe2\x96\x81SO :2.0 #0.15 @MAKE_IT_SO\n'
  printf '\xe2\x96\x81SYSTEM \xe2\x96\x81GO :2.0 #0.15 @SYSTEM_GO\n'
  printf '\xe2\x96\x81HE Y \xe2\x96\x81SYSTEM :2.0 #0.15 @HEY_SYSTEM\n'
} > "$ASSETS_DIR/keywords.txt"

echo
echo "done:"
echo "  $AAR"
echo "  $ASSETS_DIR"
