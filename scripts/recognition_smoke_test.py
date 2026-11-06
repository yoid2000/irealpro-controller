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


def recognize(samples, threshold=None, *, original=False, diagnostics=False, bias_score=0.5):
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(ASSETS / "tokens.txt"), encoder=str(ASSETS / "encoder.onnx"),
        decoder=str(ASSETS / "decoder.onnx"), joiner=str(ASSETS / "joiner.onnx"),
        num_threads=2, decoding_method="greedy_search" if original else "modified_beam_search",
        max_active_paths=4, modeling_unit="bpe", bpe_vocab=str(ASSETS / "bpe.vocab"),
        hotwords_score=bias_score, enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=1.6,
        rule3_min_utterance_length=3600)
    stream = recognizer.create_stream() if original else recognizer.create_stream("/".join(w.upper() for w in WORDS))
    detected = []
    previous = []
    stable_since = []
    last_consumed_start = -1
    committed = 0
    blocked = False
    reported = {}
    last_text = ""
    segment_start = 0
    padded = np.concatenate((samples, np.zeros(32000, dtype=np.float32)))
    for offset in range(0, len(padded), 320):
        stream.accept_waveform(16000, padded[offset:offset + 320])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
            result = recognizer.get_result_all(stream)
            tokens = result.tokens
            clock = (offset + 320 - segment_start) / 16
            common = next((i for i, (a, b) in enumerate(zip(tokens, previous)) if a != b), min(len(tokens), len(previous)))
            if original:
                assert common == len(previous)
            elif common < committed:
                blocked = True
            stable_since = [stable_since[i] if i < common else clock for i in range(len(tokens))]
            previous = list(tokens)
            endpoint = recognizer.is_endpoint(stream)
            if diagnostics and result.text != last_text:
                print(f"{(offset + 320) / 16000:.2f}s heard: {result.text}")
                last_text = result.text
            starts = [i for i, token in enumerate(tokens) if i == 0 or token.startswith((" ", "▁"))]
            for index, start in enumerate(starts):
                end = starts[index + 1] if index + 1 < len(starts) else len(tokens)
                if blocked or start <= last_consumed_start:
                    continue
                boundary = index < len(starts) - 1
                if not endpoint and (not original or not boundary) and clock - stable_since[end - 1] < 120:
                    continue
                word = "".join(tokens[start:end]).replace("▁", " ").strip().lower()
                if word not in WORDS and not boundary and not endpoint:
                    continue
                scores = result.ys_probs[start:end] if len(result.ys_probs) >= end else None
                valid = scores is not None and all(math.isfinite(score) and score <= 0 for score in scores)
                score = math.exp(sum(scores) / len(scores)) if valid else None
                word_threshold = threshold if threshold is not None else (0.15 if word in ("four", "pause") else 0.2)
                reason = ("Not a command" if word not in WORDS else
                          "Score unavailable" if scores is None else
                          "Invalid score" if not valid else
                          "Below selectivity" if score < word_threshold else "Accepted")
                signature = (word, reason)
                if diagnostics and reported.get(start) != signature:
                    print(f"{(offset + 320) / 16000:.2f}s {word}: {reason}, score={score}, threshold={word_threshold}")
                    reported[start] = signature
                if boundary or endpoint or reason == "Accepted":
                    last_consumed_start = start
                    committed = end
                if reason == "Accepted":
                    detected.append(word)
            if endpoint or blocked:
                recognizer.reset(stream)
                previous = []
                stable_since = []
                last_consumed_start = -1
                committed = 0
                blocked = False
                reported = {}
                last_text = ""
                segment_start = offset + 320
    return detected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", nargs="?", type=Path, help="16 kHz mono PCM16 speech or violin recording")
    parser.add_argument("--expect", nargs="*", help="Exact expected detections; omit words for music-only audio")
    parser.add_argument("--threshold", type=float, help="Override all word thresholds; default: app's per-word settings")
    parser.add_argument("--original", action="store_true", help="Use the previous greedy decoder without command bias")
    parser.add_argument("--diagnostics", action="store_true", help="Print transcript changes and word decisions")
    parser.add_argument("--bias-score", type=float, default=0.5)
    args = parser.parse_args()
    options = dict(original=args.original, bias_score=args.bias_score)
    assert recognize(np.zeros(16000 * 5, dtype=np.float32), args.threshold, **options) == [], "Silence triggered a command"
    print("PASS: five seconds of silence produced no commands.")
    if args.wav:
        with wave.open(str(args.wav), "rb") as audio:
            assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (16000, 1, 2)
            samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").astype(np.float32) / 32768
        detected = recognize(samples, args.threshold, diagnostics=args.diagnostics, **options)
        print(f"Audio duration: {len(samples) / 16000:.3f}s; detected: {detected}")
        if args.expect is not None:
            assert detected == args.expect, f"Expected {args.expect}, got {detected}"
            print("PASS: exact command sequence.")


if __name__ == "__main__":
    main()
