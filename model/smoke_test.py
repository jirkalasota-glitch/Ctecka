"""Rychly test exportovaneho modelu stejnym postupem, jaky pouziva aplikace (PocketVoice.kt)."""
import json, sys, time, unicodedata
import numpy as np, onnxruntime as ort

d = sys.argv[1].rstrip("/") + "/"
man = json.load(open(d + "manifest.json"))
o = ort.SessionOptions(); o.intra_op_num_threads = 2
S = lambda f: ort.InferenceSession(d + f, o, providers=["CPUExecutionProvider"])
enc, txt, main, flow, dec = S("encoder.onnx"), S("text_conditioner.onnx"), S("lm_main.int8.onnx"), S("lm_flow.int8.onnx"), S("decoder.int8.onnx")
vocab = json.load(open(d + "vocab.json", encoding="utf-8"))
score = json.load(open(d + "token_scores.json", encoding="utf-8"))
DT = {"float32": np.float32, "int64": np.int64, "bool": np.bool_}
FILL = {"nan": np.nan, "zeros": 0, "ones": 1, "empty": 0}


def init(lst):
    return {s["name"]: np.full(s["shape"], FILL[s["fill"]], dtype=DT[s["dtype"]]) for s in lst}


def tokenize(text):
    t = "▁" + " ".join(unicodedata.normalize("NFKC", text).split()).replace(" ", "▁")
    sym = list(t)
    while True:
        best, bi = None, -1
        for i in range(len(sym) - 1):
            s = score.get(sym[i] + sym[i + 1])
            if s is not None and (best is None or s > best): best, bi = s, i
        if bi < 0: break
        sym[bi:bi + 2] = [sym[bi] + sym[bi + 1]]
    return [vocab[s] for s in sym if s in vocab]


def run_main(seq, emb, st):
    out = main.run(None, {"sequence": seq, "text_embeddings": emb, **st})
    for k, s in enumerate(man["lm_states"]): st[s["name"]] = out[2 + k]
    return out[0], out[1]


rng = np.random.default_rng(0)
voice_audio = (np.sin(np.arange(24000 * 3) / 24000 * 2 * np.pi * 150) * 0.1).astype(np.float32)
voice = enc.run(None, {"audio": voice_audio[None, None]})[0]
assert voice.shape[1] > 1 and voice.shape[2] == 1024, voice.shape

text = "Dobrý den, tohle je zkouška českého hlasu."
ids = tokenize(text)
print("tokeny:", len(ids))
temb = txt.run(None, {"token_ids": np.array([ids], np.int64)})[0]
st = init(man["lm_states"])
e = np.zeros((1, 0, 32), np.float32)
run_main(e, voice, st); run_main(e, temb, st)
cur = np.full((1, 1, 32), np.nan, np.float32); et = np.zeros((1, 0, 1024), np.float32)
lat = []; eos_step = -1; t0 = time.time()
for step in range(200):
    c, eos = run_main(cur, et, st)
    if eos_step < 0 and eos[0, 0] > -4: eos_step = step
    if eos_step >= 0 and step >= eos_step + 3: break
    x = (rng.standard_normal((1, 32)) * np.sqrt(0.5)).astype(np.float32)
    for i in range(4):
        x = x + 0.25 * flow.run(None, {"c": c, "s": np.array([[i / 4]], np.float32), "t": np.array([[(i + 1) / 4]], np.float32), "x": x})[0]
    lat.append(x); cur = x[:, None]
ds = init(man["mimi_states"])
L = np.stack(lat, 1); audio = []
for i in range(0, L.shape[1], 15):
    out = dec.run(None, {"latent": L[:, i:i + 15], **ds})
    for k, s in enumerate(man["mimi_states"]): ds[s["name"]] = out[1 + k]
    audio.append(out[0].reshape(-1))
a = np.concatenate(audio)
print("snimku:", len(lat), "zvuk: %.1f s" % (len(a) / 24000), "cas: %.1f s" % (time.time() - t0))
assert np.isfinite(a).all() and 1.0 < len(a) / 24000 < 15, "spatny vystup"
print("OK")
