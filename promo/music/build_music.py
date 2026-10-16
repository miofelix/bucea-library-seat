#!/usr/bin/env python3
"""Compose, render, master and verify the original promo BGM on macOS."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run(command):
    result = subprocess.run(command, text=True, capture_output=True, check=True)
    return result.stdout + result.stderr


def main():
    for executable in ('swift', 'ffmpeg', 'ffprobe'):
        if not shutil.which(executable):
            raise SystemExit(f'Required executable is unavailable: {executable}')
    run([sys.executable, str(ROOT / 'compose_bgm.py')])
    run(['swift', str(ROOT / 'render_bgm.swift'), str(ROOT)])
    raw = ROOT / 'bucea-focus-original-raw.wav'
    final = ROOT / 'bucea-focus-original.wav'
    fades = 'afade=t=in:st=0:d=0.7,afade=t=out:st=41.5:d=3.5'
    measurement = run(['ffmpeg', '-nostdin', '-hide_banner', '-i', str(raw),
                       '-af', fades + ',loudnorm=I=-18:TP=-1.8:LRA=9:print_format=json',
                       '-f', 'null', '-'])
    match = re.search(r'\{\s*"input_i".*?\}', measurement, re.S)
    if not match:
        raise RuntimeError('Unable to read loudness measurements')
    measured = json.loads(match.group())
    mastering = ('loudnorm=I=-18:TP=-1.8:LRA=9:linear=true'
                 f':measured_I={measured["input_i"]}'
                 f':measured_TP={measured["input_tp"]}'
                 f':measured_LRA={measured["input_lra"]}'
                 f':measured_thresh={measured["input_thresh"]}'
                 f':offset={measured["target_offset"]}')
    run(['ffmpeg', '-y', '-nostdin', '-hide_banner', '-loglevel', 'error',
         '-i', str(raw), '-af', fades + ',' + mastering,
         '-ar', '48000', '-c:a', 'pcm_s24le', str(final)])
    metadata = json.loads(run(['ffprobe', '-v', 'error', '-show_streams',
                               '-show_format', '-of', 'json', str(final)]))
    validation = run(['ffmpeg', '-nostdin', '-hide_banner', '-i', str(final),
                      '-af', 'ebur128=peak=true:framelog=verbose,astats=metadata=0:reset=0',
                      '-f', 'null', '-'])
    integrated = float(re.findall(r'I:\s+(-?[\d.]+) LUFS', validation)[-1])
    true_peak = float(re.findall(r'Peak:\s+(-?[\d.]+) dBFS', validation)[-1])
    peak = float(re.findall(r'Peak level dB:\s+(-?[\d.]+)', validation)[-1])
    rms = float(re.findall(r'RMS level dB:\s+(-?[\d.]+)', validation)[-1])
    duration = float(metadata['format']['duration'])
    nans = float(re.findall(r'Number of NaNs:\s+([\d.]+)', validation)[-1])
    infinities = float(re.findall(r'Number of Infs:\s+([\d.]+)', validation)[-1])
    assert abs(duration - 45) < 0.001, duration
    assert peak < -1 and true_peak < -1, (peak, true_peak)
    assert -19 <= integrated <= -17, integrated
    assert nans == 0 and infinities == 0
    report = {
        'title': '把时间，留给专注 / Time for Focus',
        'composition': 'Original melody, harmony and arrangement for this project',
        'tempo_bpm': 90,
        'key': 'F major',
        'duration_seconds': duration,
        'sample_rate_hz': 48000,
        'channels': 2,
        'bit_depth': 24,
        'integrated_loudness_lufs': integrated,
        'true_peak_dbfs': true_peak,
        'sample_peak_dbfs': peak,
        'rms_dbfs': rms,
        'nan_samples': nans,
        'infinite_samples': infinities,
        'fade_in_seconds': 0.7,
        'fade_out_start_seconds': 41.5,
        'fade_out_seconds': 3.5,
        'instrument_source': 'macOS built-in General MIDI gs_instruments.dls',
        'third_party_song_samples': False,
    }
    (ROOT / 'music-validation.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
