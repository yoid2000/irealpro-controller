"""Exercise the same model, keywords, thresholds and 20 ms feed used on Android.

Install optional test dependencies from requirements-test.txt. This does not model
Bluetooth audio quality or establish real-world violin rejection performance.
"""
import argparse
import math
from pathlib import Path
import wave

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/kws"
WORDS = ["one", "two", "three", "four", "start", "stop", "pause"]


def recognize(samples, threshold):
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(ASSETS / "tokens.txt"), encoder=str(ASSETS / "encoder.onnx"),
        decoder=str(ASSETS / "decoder.onnx"), joiner=str(ASSETS / "joiner.onnx"),
        num_threads=2, decoding_method="greedy_search", enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
        rule3_min_utterance_length=3600)
    stream = recognizer.create_stream()
    detected = []
    previous = []
    changed_at = 0
    last_consumed_start = -1
    segment_start = 0
    padded = np.concatenate((samples, np.zeros(32000, dtype=np.float32)))
    for offset in range(0, len(padded), 320):
        stream.accept_waveform(16000, padded[offset:offset + 320])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
            result = recognizer.get_result_all(stream)
            tokens = result.tokens
            assert tokens[:len(previous)] == previous
            clock = (offset + 320 - segment_start) / 16
            if tokens != previous:
                changed_at = clock
                previous = list(tokens)
            endpoint = recognizer.is_endpoint(stream)
            starts = [i for i, token in enumerate(tokens) if i == 0 or token.startswith((" ", "▁"))]
            for index, start in enumerate(starts):
                end = starts[index + 1] if index + 1 < len(starts) else len(tokens)
                if start <= last_consumed_start:
                    continue
                boundary = index < len(starts) - 1
                if not boundary and not endpoint and clock - changed_at < 120:
                    continue
                word = "".join(tokens[start:end]).replace("▁", " ").strip().lower()
                if boundary or endpoint:
                    last_consumed_start = start
                if word not in WORDS or len(result.ys_probs) < end:
                    continue
                scores = result.ys_probs[start:end]
                if any(not math.isfinite(score) or score > 0 for score in scores):
                    continue
                word_threshold = 0.4 if word == "pause" and threshold == 0.5 else threshold
                if math.exp(sum(scores) / len(scores)) >= word_threshold:
                    detected.append(word)
                    last_consumed_start = start
            if endpoint:
                recognizer.reset(stream)
                previous = []
                changed_at = 0
                last_consumed_start = -1
                segment_start = offset + 320
    return detected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", nargs="?", type=Path, help="16 kHz mono PCM16 speech or violin recording")
    parser.add_argument("--expect", nargs="*", help="Exact expected detections; omit words for music-only audio")
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    assert recognize(np.zeros(16000 * 5, dtype=np.float32), args.threshold) == [], "Silence triggered a command"
    print("PASS: five seconds of silence produced no commands.")
    if args.wav:
        with wave.open(str(args.wav), "rb") as audio:
            assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (16000, 1, 2)
            samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").astype(np.float32) / 32768
        detected = recognize(samples, args.threshold)
        print(f"Audio duration: {len(samples) / 16000:.3f}s; detected: {detected}")
        if args.expect is not None:
            assert detected == args.expect, f"Expected {args.expect}, got {detected}"
            print("PASS: exact command sequence.")


if __name__ == "__main__":
    main()
