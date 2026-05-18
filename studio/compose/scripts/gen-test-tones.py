#!/usr/bin/env python3
"""
Generates synthetic sine wave WAV files used as test fixtures by
AudioScenePopulationBatchedDispatchTest. Run from anywhere:

    python3 studio/compose/scripts/gen-test-tones.py

This is a one-shot generator. Outputs are committed to the repository;
the script exists so a future agent can regenerate the fixtures if needed.
"""

import math
import os
import struct
import wave


SAMPLE_RATE = 44100
DURATION_SECONDS = 0.5
AMPLITUDE = 0.5

# Each tuple: (filename, frequency_hz). Names start with "TestTone " so the
# pattern factory's NAME_STARTS_WITH filter matches them. Frequencies are
# octave-ish steps so the pattern's pitch-shift resampling has variety.
TONES = [
    ("TestTone C2.wav", 65.41),
    ("TestTone E2.wav", 82.41),
    ("TestTone G2.wav", 98.00),
    ("TestTone C3.wav", 130.81),
]


def synthesize(freq_hz, duration_s, sample_rate):
    n = int(round(duration_s * sample_rate))
    fade = max(1, int(0.01 * sample_rate))  # 10ms fade-in/out to avoid clicks
    samples = []
    for i in range(n):
        t = i / sample_rate
        env = 1.0
        if i < fade:
            env = i / fade
        elif i > n - fade:
            env = max(0.0, (n - i) / fade)
        s = AMPLITUDE * env * math.sin(2.0 * math.pi * freq_hz * t)
        samples.append(int(round(max(-1.0, min(1.0, s)) * 32767)))
    return samples


def write_wav(path, samples, sample_rate):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(b"".join(struct.pack("<h", s) for s in samples))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    compose_module = os.path.dirname(here)
    out_dir = os.path.join(
        compose_module,
        "src", "test", "resources",
        "test-fixtures", "Library",
    )
    os.makedirs(out_dir, exist_ok=True)
    for name, freq in TONES:
        path = os.path.join(out_dir, name)
        samples = synthesize(freq, DURATION_SECONDS, SAMPLE_RATE)
        write_wav(path, samples, SAMPLE_RATE)
        print("wrote", path, len(samples), "samples")


if __name__ == "__main__":
    main()
