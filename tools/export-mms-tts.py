"""
Convert a Meta MMS text-to-speech model to the ONNX form sherpa-onnx loads.

Why this exists: sherpa-onnx publishes no Tamil voice, and AI4Bharat's Tamil models
ship only as NeMo checkpoints. MMS covers Tamil, is CC-BY-NC 4.0, and converts with
nothing heavier than torch + transformers.

    python tools/export-mms-tts.py --lang tam --out build/tts-tam

MMS uses its own character vocabulary and needs no espeak dictionary, so the resulting
pack is much smaller than a Piper voice: one .onnx plus a tokens file.

LICENCE NOTE: MMS-TTS is released under CC-BY-NC 4.0 — non-commercial use only. That is
fine for a competition entry and for research, and it must be stated in the submission.
It is NOT compatible with a commercial deployment; that would need a differently
licensed Tamil voice.
"""

import argparse
import json
import os
import sys

# torch's exporter prints status with emoji. On a Windows console defaulting to cp1252
# that print raises UnicodeEncodeError and masks the real export error underneath.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import torch
from transformers import VitsModel, AutoTokenizer


class Wrapper(torch.nn.Module):
    """
    Exposes the VITS generator with a fixed signature ONNX can trace.

    The HF model returns a dataclass; ONNX needs plain tensors, and sherpa expects
    (tokens, token_lengths, scales) in that order.
    """

    def __init__(self, model: VitsModel):
        super().__init__()
        self.model = model

    def forward(self, x, x_length, noise_scale, length_scale, noise_scale_w):
        # Assign the TENSORS, never float(them). Casting to a Python float bakes the
        # value into the traced graph as a constant, and the exported model then has a
        # single input where sherpa feeds five — which it treats as a fatal mismatch
        # and aborts the process over. Keeping them as tensors keeps them as inputs.
        self.model.noise_scale = noise_scale
        self.model.speaking_rate = length_scale
        self.model.noise_scale_duration = noise_scale_w

        # sherpa passes the token count as x_length. HuggingFace's VITS takes an
        # attention mask instead, so derive one from it — which also keeps x_length
        # genuinely used, and therefore kept as a graph input rather than traced away.
        mask = (
            torch.arange(x.shape[1], device=x.device).unsqueeze(0)
            < x_length.unsqueeze(1)
        ).long()
        return self.model(input_ids=x, attention_mask=mask).waveform


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="tam", help="MMS language code, e.g. tam")
    ap.add_argument("--out", default="build/tts", help="output directory")
    args = ap.parse_args()

    repo = f"facebook/mms-tts-{args.lang}"
    os.makedirs(args.out, exist_ok=True)

    print(f"loading {repo} …")
    model = VitsModel.from_pretrained(repo)
    tokenizer = AutoTokenizer.from_pretrained(repo)
    model.eval()

    # sherpa reads the vocabulary as "symbol id" lines, one per line.
    #
    # Two rules, both learned the hard way. Each cost a full build-push-test cycle,
    # because on the phone a violation presents as an uncatchable native abort with no
    # message — not as an error naming the file. Running the same model through the
    # desktop sherpa-onnx prints the real cause in one second; do that first next time.
    #
    #  1. The space symbol is written AS A LITERAL SPACE. sherpa's character frontend
    #     splits each line on the last space, so a literal space parses correctly.
    #     Substituting a placeholder such as "<space>" also violates rule 2.
    #
    #  2. Every symbol must be EXACTLY ONE CHARACTER, or the model will not load at all
    #     (offline-tts-character-frontend.cc:ReadTokens rejects the file). MMS
    #     vocabularies contain "<unk>", which is five characters, so it has to be
    #     dropped — this single line was what kept Tamil silent. Dropping it loses
    #     nothing: the frontend already skips characters it has no token for.
    #
    # Newlines are forced to LF: a CRLF file leaves a stray carriage return attached to
    # the id, so the last field stops parsing as an integer.
    vocab = tokenizer.get_vocab()
    tokens_path = os.path.join(args.out, "tokens.txt")
    written, skipped = 0, []
    with open(tokens_path, "w", encoding="utf-8", newline="\n") as f:
        for symbol, idx in sorted(vocab.items(), key=lambda kv: kv[1]):
            if len(symbol) != 1:
                skipped.append(symbol)
                continue
            f.write(f"{symbol} {idx}\n")
            written += 1
    print(f"wrote {tokens_path} ({written} symbols)")
    if skipped:
        print(f"  dropped {len(skipped)} non-single-character symbol(s): {skipped}")

    example = tokenizer("வணக்கம்", return_tensors="pt")
    x = example["input_ids"]
    x_lengths = torch.tensor([x.shape[1]], dtype=torch.int64)

    onnx_path = os.path.join(args.out, "model.onnx")
    torch.onnx.export(
        Wrapper(model),
        (
            x,
            x_lengths,
            torch.tensor([0.667], dtype=torch.float32),
            torch.tensor([1.0], dtype=torch.float32),
            torch.tensor([0.8], dtype=torch.float32),
        ),
        onnx_path,
        opset_version=14,
        # Names must match sherpa's VITS loader exactly: "x_length", not "x_lengths".
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"],
        output_names=["y"],
        dynamic_axes={
            "x": {0: "N", 1: "L"},
            "x_length": {0: "N"},
            "y": {0: "N", 2: "L"},
        },
        # Legacy TorchScript tracer. The newer dynamo path cannot capture this model
        # (VITS samples from a distribution during inference, which torch.export
        # refuses to trace), and its own failure message crashes on Windows.
        dynamo=False,
    )
    print(f"wrote {onnx_path}")

    # sherpa reads these from the ONNX metadata rather than a side-car file.
    import onnx

    m = onnx.load(onnx_path)
    # Verified against a known-good sherpa MMS model (vits-mms-eng). Getting any of
    # these wrong makes sherpa abort the whole process natively, with no Java exception
    # and no message — which is exactly how the first attempt failed.
    #
    #   frontend=characters  MMS is trained on raw characters. Without this sherpa
    #                        looks for a pronunciation lexicon that does not exist.
    #   n_speakers=0         Not 1. A single-speaker model declares zero.
    meta = {
        "model_type": "vits",
        "comment": "mms",
        "url": f"https://huggingface.co/facebook/mms-tts-{args.lang}",
        "add_blank": int(getattr(model.config, "add_blank", True)),
        "language": args.lang,
        "frontend": "characters",
        "n_speakers": 0,
        "sample_rate": model.config.sampling_rate,
    }
    for k, v in meta.items():
        entry = m.metadata_props.add()
        entry.key = str(k)
        entry.value = str(v)
    onnx.save(m, onnx_path)
    print("metadata:", json.dumps(meta))
    print(f"sample rate: {model.config.sampling_rate} Hz")


if __name__ == "__main__":
    main()
