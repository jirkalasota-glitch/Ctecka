"""Doladi exportovany cesky Pocket TTS pro aplikaci:
- vlozi BOS pred vzorek hlasu primo do mimi_encoder (cesky model ho vyzaduje)
- kvantizuje hlavni casti na int8
- vytvori vocab.json + token_scores.json pro BPE tokenizer v aplikaci
- zapise manifest.json (stavy modelu, seznam souboru)
"""
import json, sys, numpy as np, onnx, sentencepiece as spm
from pathlib import Path
from onnx import helper, numpy_helper
from onnxruntime.quantization import quantize_dynamic, QuantType

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
dst.mkdir(parents=True, exist_ok=True)

# 1) BOS do enkoderu
bos = np.load(src / "bos_before_voice.npy").astype(np.float32).reshape(1, 1, -1)
m = onnx.load(src / "mimi_encoder.onnx")
g = m.graph; out = g.output[0]; old = out.name
for n in g.node:
    for i, o in enumerate(n.output):
        if o == old: n.output[i] = old + "_raw"
g.initializer.append(numpy_helper.from_array(bos, "bos_before_voice"))
g.node.append(helper.make_node("Concat", ["bos_before_voice", old + "_raw"], [old], axis=1))
out.type.tensor_type.shape.dim[1].dim_param = "voice_len"
onnx.checker.check_model(m)
onnx.save(m, dst / "encoder.onnx")

# 2) int8
for a, b in [("flow_lm_main", "lm_main.int8"), ("flow_lm_flow", "lm_flow.int8"), ("mimi_decoder", "decoder.int8")]:
    quantize_dynamic(str(src / f"{a}.onnx"), str(dst / f"{b}.onnx"), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
(dst / "text_conditioner.onnx").write_bytes((src / "text_conditioner.onnx").read_bytes())

# 3) tokenizer
sp = spm.SentencePieceProcessor(model_file=str(src / "tokenizer.model"))
vocab = {sp.id_to_piece(i): i for i in range(sp.get_piece_size())}
scores = {sp.id_to_piece(i): sp.get_score(i) for i in range(sp.get_piece_size())}
json.dump(vocab, open(dst / "vocab.json", "w", encoding="utf-8"), ensure_ascii=False)
json.dump(scores, open(dst / "token_scores.json", "w", encoding="utf-8"), ensure_ascii=False)

# 4) manifest
b = json.load(open(src / "bundle.json"))
def states(lst):
    return [{"name": s["input_name"], "out": s["output_name"], "dtype": s["dtype"], "fill": s["fill"], "shape": s["shape"]} for s in lst]
man = {
    "version": 1,
    "sample_rate": 24000,
    "lm_states": states(b["flow_lm_state_manifest"]),
    "mimi_states": states(b["mimi_state_manifest"]),
    "files": sorted(p.name for p in dst.iterdir() if p.name != "manifest.json"),
}
json.dump(man, open(dst / "manifest.json", "w"), indent=1)
for p in sorted(dst.iterdir()): print(p.name, p.stat().st_size)
