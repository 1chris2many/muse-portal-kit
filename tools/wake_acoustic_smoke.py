#!/usr/bin/env python3
"""Offline synthetic wake smoke test. Never accesses a microphone, speaker or Muse.

Generate synthetic fixture files on macOS: --generate /path/to/private/temp-dir
Run on Linux with vosk==0.3.45: --fixtures DIR --model DIR
Tests use the same model and match rule as Android, but a different host binding.
This is not a far-field, real-speaker, hardware-load or privacy-switch benchmark.
Generated audio is not checked into the repository.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import wave

CASES = [
    ("nova-us", "Samantha", "Nova", True),
    ("nova-uk", "Daniel", "Nova", True),
    ("no", "Samantha", "No", False),
    ("never", "Samantha", "Never", False),
    ("over", "Samantha", "Over", False),
    ("supernova", "Samantha", "Supernova", False),
    ("nova-scotia", "Samantha", "Nova Scotia", False),
    ("november", "Samantha", "November", False),
    ("know-her", "Samantha", "Know her", False),
    ("question", "Samantha", "What time is it?", False),
]


def generate(destination):
    if sys.platform != "darwin":
        raise RuntimeError("Synthetic fixture generation requires macOS say/afconvert")
    destination.mkdir(parents=True, exist_ok=True)
    for name, voice, phrase, _ in CASES:
        aiff, wav = destination / (name + ".aiff"), destination / (name + ".wav")
        if aiff.exists() or wav.exists():
            raise FileExistsError("Refusing to overwrite an existing fixture")
        # -o directs synthesis into a file: nothing is played through the speaker.
        subprocess.run(["/usr/bin/say", "-v", voice, "-o", str(aiff), phrase], check=True)
        subprocess.run(["/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)], check=True)
    print(f"Generated {len(CASES)} synthetic files; no audio played")


def matches(result):
    words = result.get("result", [])
    if result.get("text", "").strip().lower() != "nova" or len(words) != 1:
        return False
    word = words[0]
    return (word.get("word", "").lower() == "nova" and 0.90 <= word.get("conf", 0) <= 1.0
            and 0.12 <= word.get("end", 0) - word.get("start", 0) <= 1.8)


def check(model_path, fixtures):
    import vosk
    vosk.SetLogLevel(-1)
    model = vosk.Model(str(model_path))
    outcomes = []
    for name, _, _, expected in CASES:
        with wave.open(str(fixtures / (name + ".wav"))) as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
                raise ValueError("Expected mono PCM16 at 16 kHz")
            pcm = bytes(16000) + source.readframes(source.getnframes()) + bytes(64000)
        recognizer = vosk.KaldiRecognizer(model, 16000)
        recognizer.SetWords(True)
        hits = 0
        for pos in range(0, len(pcm), 3200):
            if recognizer.AcceptWaveform(pcm[pos:pos + 3200]):
                hits += int(matches(json.loads(recognizer.Result())))
        # Production uses endpoint-complete results, not a forced partial FinalResult.
        outcomes.append({"fixture": name, "expected": int(expected), "detections": hits,
                         "pass": hits == int(expected)})
    # Silence can never qualify.
    recognizer = vosk.KaldiRecognizer(model, 16000)
    recognizer.SetWords(True)
    hits = 0
    for _ in range(100):
        if recognizer.AcceptWaveform(bytes(3200)):
            hits += int(matches(json.loads(recognizer.Result())))
    outcomes.append({"fixture": "silence-10s", "expected": 0, "detections": hits, "pass": hits == 0})
    print(json.dumps(outcomes, indent=2))
    return all(row["pass"] for row in outcomes)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", type=Path)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--model", type=Path)
    args = parser.parse_args()
    if args.generate:
        generate(args.generate)
    elif args.fixtures and args.model:
        sys.exit(0 if check(args.model, args.fixtures) else 1)
    else:
        parser.error("Supply --generate DIR or both --fixtures DIR and --model DIR")
