"""
Tiny stdin/stdout model server used by core's RealModelPipelineTest: lets the Kotlin MultiPosePipeline run with the
real BlazePose .tflite models on the JVM (inference itself is done here with ai-edge-litert).

Protocol (little-endian float32):
  request : 1 byte op ('D' detector, 'L' landmarks, 'H' hand landmarks, 'Q' quit) + 1 byte variant ('l' lite,
            'f' full) + input floats
  response: detector  -> 2254*12 regressors + 2254 scores
            landmarks -> 195 landmarks + 1 flag + 64*64*39 heatmap
            hand      -> 63 hand landmarks + 1 presence
Usage: python tflite_server.py <models_dir>   (models_dir contains lite/ and full/ with the two pose .tflite files,
       and hand/hand_landmark_lite.tflite and hand/hand_landmark_full.tflite; variant 'l'/'f' picks the hand model)
"""
import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter

models = sys.argv[1]
cache = {}


def interp(variant, kind):
    key = (variant, kind)
    if key not in cache:
        it = Interpreter(model_path=f"{models}/{variant}/{kind}.tflite")
        it.allocate_tensors()
        outs = {d["name"]: d["index"] for d in it.get_output_details()}
        cache[key] = (it, it.get_input_details()[0]["index"], outs)
    return cache[key]


inp = sys.stdin.buffer
out = sys.stdout.buffer
while True:
    hdr = inp.read(2)
    if len(hdr) < 2 or hdr[0:1] == b"Q":
        break
    op = hdr[0:1]
    variant = "lite" if hdr[1:2] == b"l" else "full"
    if op == b"D":
        n = 224 * 224 * 3
        x = np.frombuffer(inp.read(n * 4), dtype="<f4").reshape(1, 224, 224, 3)
        it, i, o = interp(variant, "pose_detector")
        it.set_tensor(i, x)
        it.invoke()
        out.write(it.get_tensor(o["Identity"]).astype("<f4").tobytes())
        out.write(it.get_tensor(o["Identity_1"]).astype("<f4").tobytes())
    elif op == b"L":
        n = 256 * 256 * 3
        x = np.frombuffer(inp.read(n * 4), dtype="<f4").reshape(1, 256, 256, 3)
        it, i, o = interp(variant, "pose_landmarks_detector")
        it.set_tensor(i, x)
        it.invoke()
        out.write(it.get_tensor(o["Identity"]).astype("<f4").tobytes())
        out.write(it.get_tensor(o["Identity_1"]).astype("<f4").tobytes())
        out.write(it.get_tensor(o["Identity_3"]).astype("<f4").tobytes())
    elif op == b"H":
        n = 224 * 224 * 3
        x = np.frombuffer(inp.read(n * 4), dtype="<f4").reshape(1, 224, 224, 3)
        it, i, o = interp("hand", "hand_landmark_lite" if variant == "lite" else "hand_landmark_full")
        it.set_tensor(i, x)
        it.invoke()
        out.write(it.get_tensor(o["Identity"]).astype("<f4").tobytes())
        out.write(it.get_tensor(o["Identity_1"]).astype("<f4").tobytes())
    out.flush()
