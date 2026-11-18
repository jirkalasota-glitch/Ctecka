#!/usr/bin/env bash
# Export ceskeho Pocket TTS do ONNX pro aplikaci (bezi v GitHub Actions)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/pocket-export}
OUT=${OUT:-$HERE/out}
rm -rf "$WORK" && mkdir -p "$WORK"
git clone -q https://github.com/alfredomariamilano/pocket-tts-onnx-export.git "$WORK/exp"
cd "$WORK/exp"
git checkout -q 953c746789f7042cf0c2dcc257e285562b234813
git submodule update --init --recursive -q
git -C third_party/pocket-tts checkout -q 15a6c1817b360f9b37691aef9734435a85610c68
# --config nesmi kolidovat s vychozim --language
sed -i 's/parser.add_argument("--language", type=str, default=DEFAULT_LANGUAGE,/parser.add_argument("--language", type=str, default=None,/' scripts/export_mimi_and_conditioner.py scripts/export_flow_lm.py
# mensi KV cache = 2x rychlejsi beh v mobilu (vety jsou kratke)
sed -i 's/    STATIC_SEQ_LEN = 4096/    STATIC_SEQ_LEN = 1024/' scripts/export_flow_lm.py
grep -q "STATIC_SEQ_LEN = 1024" scripts/export_flow_lm.py
export PYTHONPATH=.:third_party/pocket-tts
python -m scripts.export_mimi_and_conditioner --output_dir "$WORK/raw" --config "$HERE/czech.yaml" 2>&1 | grep -vE "matches within|Warning" | tail -40
chmod -R u+w "$WORK/raw"   # tokenizer z HF cache je jen pro cteni
python -m scripts.export_flow_lm --output_dir "$WORK/raw" --config "$HERE/czech.yaml" 2>&1 | grep -vE "matches within|Warning" | tail -40
chmod -R u+w "$WORK/raw"
python "$HERE/finalize.py" "$WORK/raw" "$OUT"
