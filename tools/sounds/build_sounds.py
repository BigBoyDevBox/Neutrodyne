#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Build the radio's control sounds (08 Control sounds, PO-56) from their public-domain sources.

    uv run --with numpy --with scipy --with soundfile python3 tools/sounds/build_sounds.py

Reads media-sources/sounds/sounds.json and the recordings it names under
media-sources/sounds/sources/, and writes one 48 kHz mono 16-bit PCM WAV per sound to
core/designsystem/src/commonMain/composeResources/files/sounds/. For each sound it takes the
listed channel (or the mix), cuts the [start, end) span in seconds, filters out rumble and
anything above the cut-off, resamples to 48 kHz, fades both ends, and scales it so its loudest
20 ms reads at the shared target level with the peak kept below the ceiling. Rounding replaces
dither, so the output is the same on every run.
"""

from __future__ import annotations

import json
import sys
from fractions import Fraction
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "media-sources/sounds/sounds.json"
SOURCES = ROOT / "media-sources/sounds/sources"
OUTPUT = ROOT / "core/designsystem/src/commonMain/composeResources/files/sounds"

RATE = 48_000
HIGH_PASS_HZ = 70.0
FADE_IN_S = 0.002
TARGET_RMS_DBFS = -18.0  # loudest 20 ms window
PEAK_CEILING_DBFS = -3.0
WINDOW_S = 0.020


def db_to_gain(db: float) -> float:
    return 10.0 ** (db / 20.0)


def loudest_window_rms(x: np.ndarray) -> float:
    n = int(RATE * WINDOW_S)
    if len(x) <= n:
        return float(np.sqrt(np.mean(x**2)))
    power = np.convolve(x**2, np.ones(n) / n, mode="valid")
    return float(np.sqrt(power.max()))


def build(entry: dict) -> np.ndarray:
    audio, rate = sf.read(SOURCES / entry["source"], always_2d=True, dtype="float64")
    channel = entry.get("channel")
    mono = audio[:, channel] if channel is not None else audio.mean(axis=1)

    start, end = (int(round(t * rate)) for t in (entry["start"], entry["end"]))
    if not 0 <= start < end <= len(mono):
        raise SystemExit(f"{entry['name']}: span {entry['start']}..{entry['end']} s outside the source")
    # Filter with some context on both sides, then cut, so the filters do not ring at the edges.
    pad = int(0.05 * rate)
    lo, hi = max(0, start - pad), min(len(mono), end + pad)
    piece = mono[lo:hi]

    nyquist = rate / 2
    sos = signal.butter(2, HIGH_PASS_HZ / nyquist, "highpass", output="sos")
    piece = signal.sosfiltfilt(sos, piece)
    cutoff = min(entry.get("low_pass_hz", 18_000.0), 0.45 * rate, 0.45 * RATE)
    sos = signal.butter(4, cutoff / nyquist, "lowpass", output="sos")
    piece = signal.sosfiltfilt(sos, piece)
    piece = piece[start - lo : end - lo]

    ratio = Fraction(RATE, rate).limit_denominator(1000)
    if ratio != 1:
        piece = signal.resample_poly(piece, ratio.numerator, ratio.denominator)

    fade_in = int(FADE_IN_S * RATE)
    fade_out = int(entry["fade_out"] * RATE)
    piece[:fade_in] *= np.sin(np.linspace(0, np.pi / 2, fade_in)) ** 2
    piece[len(piece) - fade_out :] *= np.cos(np.linspace(0, np.pi / 2, fade_out)) ** 2

    gain = db_to_gain(TARGET_RMS_DBFS + entry.get("trim_db", 0.0)) / loudest_window_rms(piece)
    peak = np.abs(piece).max() * gain
    ceiling = db_to_gain(PEAK_CEILING_DBFS)
    if peak > ceiling:
        gain *= ceiling / peak
    return piece * gain


def main() -> int:
    entries = json.loads(MANIFEST.read_text(encoding="utf-8"))["sounds"]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        pcm = np.clip(np.round(build(entry) * 32767.0), -32768, 32767).astype(np.int16)
        path = OUTPUT / f"{entry['name']}.wav"
        sf.write(path, pcm, RATE, subtype="PCM_16", format="WAV")
        print(f"{path.relative_to(ROOT)}: {len(pcm) / RATE:.3f} s, peak {20 * np.log10(np.abs(pcm).max() / 32768):.1f} dBFS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
