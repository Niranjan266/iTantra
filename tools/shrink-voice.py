#!/usr/bin/env python3
"""
Halve a voice model's size on disk without making it cost more CPU to run.

    python tools/shrink-voice.py in.onnx out.onnx

## Why not int8, and why not a plain fp16 conversion

Measured on one CPU thread, seconds of CPU per second of speech:

    Tamil MMS   int8 (ConvInteger)   15.70     fp32  ~0.2
    Piper       int8 (ConvInteger)    0.46     fp32   0.08

Dynamic int8 quantisation turns every convolution into ConvInteger, for which
onnxruntime has no fast CPU kernel. The file shrinks 4x and each sentence costs 5x to
80x the CPU — the opposite of what a phone on battery needs. It was why Tamil took
twelve seconds to say three.

A true fp16 model does the arithmetic in half precision, which onnxruntime's CPU
provider mostly does not accelerate either, and sherpa-onnx's published fp16 Piper
voices do not even load (a Cast emits float16 where the next node expects float).

## What this does instead

Every large float weight is STORED as fp16 and followed by a Cast back to fp32. The
graph's arithmetic is untouched. onnxruntime constant-folds a Cast whose input is an
initializer when the session is created, so the conversion is paid once at load and
never again: the running model is the fp32 model, from a file half the size.
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

MIN_ELEMENTS = 1024   # tiny tensors are not worth a node each


def shrink(src: str, dst: str) -> None:
    m = onnx.load(src)
    g = m.graph
    keep, casts, n = [], [], 0
    for init in g.initializer:
        if init.data_type != TensorProto.FLOAT or int(np.prod(init.dims or [1])) < MIN_ELEMENTS:
            keep.append(init)
            continue
        arr = numpy_helper.to_array(init)
        # Refuse to clip: a weight beyond fp16's range would silently become inf.
        if np.abs(arr).max() > 65000:
            keep.append(init)
            continue
        half = numpy_helper.from_array(arr.astype(np.float16), init.name + "__fp16")
        keep.append(half)
        casts.append(helper.make_node("Cast", [half.name], [init.name],
                                      to=TensorProto.FLOAT, name=init.name + "__to_fp32"))
        n += 1
    del g.initializer[:]
    g.initializer.extend(keep)
    # Casts first, so every consumer finds its input already defined (topological order).
    nodes = casts + list(g.node)
    del g.node[:]
    g.node.extend(nodes)
    # Some published voices carry the same metadata key twice (sherpa's Piper packaging
    # does). The checker rejects that; keep the last value, which is what a reader that
    # builds a map from the list would have seen anyway.
    meta = {p.key: p.value for p in m.metadata_props}
    del m.metadata_props[:]
    for k, v in meta.items():
        m.metadata_props.add(key=k, value=v)
    onnx.checker.check_model(m)
    onnx.save(m, dst)
    print(f"{n} weights stored as fp16 -> {dst}")


if __name__ == "__main__":
    shrink(sys.argv[1], sys.argv[2])
