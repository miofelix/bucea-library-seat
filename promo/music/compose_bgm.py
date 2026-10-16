#!/usr/bin/env python3
"""Write an original 45-second, 90 BPM instrumental MIDI using only Python.

The composition and arrangement are original project assets.  Rendering uses
macOS's built-in General MIDI sampler; no third-party song is sampled.
"""
from __future__ import annotations

import random
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TPQ = 960
BPM = 90
RNG = random.Random(20261016)


def variable_length(number: int) -> bytes:
    out = [number & 0x7f]
    while number >> 7:
        number >>= 7
        out.insert(0, (number & 0x7f) | 0x80)
    return bytes(out)


def chunk(events: list[tuple[int, bytes]]) -> bytes:
    events.sort(key=lambda event: (event[0], event[1][0] & 0xf0 != 0x80))
    data = bytearray()
    position = 0
    for tick, payload in events:
        data.extend(variable_length(tick - position))
        data.extend(payload)
        position = tick
    data.extend(b'\0\xff\x2f\0')
    return b'MTrk' + struct.pack('>I', len(data)) + data


def track(name: str, channel: int, program: int, volume: int, pan: int):
    label = name.encode('ascii')
    return [(0, b'\xff\x03' + variable_length(len(label)) + label),
            (0, bytes([0xc0 | channel, program])),
            (0, bytes([0xb0 | channel, 7, volume])),
            (0, bytes([0xb0 | channel, 10, pan])),
            (0, bytes([0xb0 | channel, 91, 0]))]


def note(events, channel, pitch, beat, duration, velocity, loosen=True):
    # Small timing/velocity differences retain the human phrasing of each line.
    offset = RNG.uniform(-0.010, 0.010) if loosen else 0
    start = max(0, round((beat + offset) * TPQ))
    stop = round((beat + offset + duration) * TPQ)
    velocity = max(1, min(127, int(velocity + RNG.uniform(-3, 3))))
    events.extend([(start, bytes([0x90 | channel, pitch, velocity])),
                   (stop, bytes([0x80 | channel, pitch, 0]))])


# Wide, open voicings avoid low bass and leave the lead plenty of space.
# Fmaj9, C/E, Dm9, Bbmaj7, F/A, Gm9, Bbmaj9, Csus/C;
# Fmaj9, Am7, Dm9, Bbmaj7, Gm9, Cadd9, Fmaj9, F6.
chords = [
    [53, 60, 64, 67, 69], [52, 60, 64, 67, 74],
    [50, 57, 60, 64, 65], [46, 53, 57, 60, 65],
    [57, 60, 65, 67, 72], [55, 58, 62, 65, 69],
    [46, 53, 57, 60, 65], [48, 55, 60, 62, 67],
    [53, 60, 64, 67, 69], [45, 52, 55, 60, 64],
    [50, 57, 60, 64, 65], [46, 53, 57, 60, 65],
    [55, 58, 62, 65, 69], [48, 55, 60, 62, 64],
    [53, 60, 64, 67, 69], [53, 60, 62, 65, 69],
]

# Original answering phrases, in F major.  Each tuple is pitch, beat, duration.
melody = [
    [(69,.50,.65),(72,1.25,.55),(76,2.0,.65),(74,2.75,.45),(72,3.25,.65)],
    [(67,0,.85),(72,1.25,.60),(74,2.0,.65),(76,3.0,.80)],
    [(77,0,1.25),(76,1.5,.55),(74,2.25,.55),(72,3.0,.80)],
    [(69,0,.75),(65,1.0,.75),(67,2.0,.55),(69,2.75,1.0)],
    [(72,0,.70),(69,1.0,.55),(72,1.75,.55),(76,2.5,1.10)],
    [(74,0,1.15),(72,1.5,.55),(70,2.25,.55),(69,3.0,.70)],
    [(65,0,.75),(69,1.0,.65),(72,2.0,.65),(74,2.75,1.0)],
    [(72,0,1.2),(67,1.75,.65),(64,2.75,.75)],
    [(69,0,.65),(72,.75,.55),(76,1.5,.65),(79,2.5,1.1)],
    [(76,0,1.15),(74,1.5,.55),(72,2.25,.55),(71,3.0,.7)],
    [(69,0,.70),(72,1.0,.65),(77,2.0,1.35)],
    [(76,0,.6),(74,.75,.6),(72,1.75,.6),(69,2.75,.9)],
    [(70,0,.65),(74,1.0,.65),(77,2.0,.65),(74,3.0,.65)],
    [(72,0,.65),(74,.75,.6),(76,1.75,.6),(67,2.75,.9)],
    [(69,0,1.0),(72,1.5,.65),(76,2.5,.65),(74,3.25,.5)],
    [(72,0,1.15),(69,1.5,.8),(65,2.75,1.15)],
]


def main():
    piano = track('Soft piano melody', 0, 0, 94, 61)
    keys = track('Mellow electric piano arpeggio', 1, 4, 68, 49)
    mallet = track('Vibraphone glints', 2, 11, 53, 81)
    pad = track('Warm pad', 3, 89, 31, 64)
    drum = track('Gentle rhythm', 9, 0, 43, 64)

    for bar, (voicing, phrase) in enumerate(zip(chords, melody)):
        base = bar * 4
        strength = 64 if bar < 8 else 68
        if bar >= 14:
            strength -= 6
        for pitch, beat, duration in phrase:
            note(piano, 0, pitch, base + beat, duration, strength)

        # A lilting broken chord, with intentional gaps in the last bar.
        pattern = [0, 2, 3, 4, 1, 2, 3, 2]
        for step, index in enumerate(pattern):
            if bar == 15 and step > 4:
                continue
            note(keys, 1, voicing[index], base + step * .5,
                 .7 if step % 2 else .85, 40 if step % 2 else 47)
        for pitch in voicing[1:]:
            note(pad, 3, pitch, base + .04, 4.1, 43, loosen=False)

        # The vibraphone quietly answers the melody at phrase boundaries.
        if bar in (1, 3, 5, 7, 9, 11, 13):
            for beat, pitch in [(2.50, voicing[2] + 12), (3.25, voicing[3] + 12)]:
                note(mallet, 2, pitch, base + beat, .65, 45)
        if bar == 15:
            note(mallet, 2, 77, base + 2.0, 1.7, 38)

        # Minimal closed hats, soft rim clicks and a very light bass drum.
        # Intro and closing bars breathe with fewer percussion notes.
        if 2 <= bar < 15:
            for step in range(8):
                note(drum, 9, 42, base + step * .5 + .02,
                     .08, 23 if step % 2 else 29)
            note(drum, 9, 37, base + 1.0, .1, 42)
            note(drum, 9, 37, base + 3.0, .1, 39)
            note(drum, 9, 36, base, .12, 27)
            if bar % 2:
                note(drum, 9, 36, base + 2.5, .12, 23)

    tempo = [(0, b'\xff\x03\x05Tempo'),
             (0, b'\xff\x51\x03' + round(60_000_000 / BPM).to_bytes(3, 'big')),
             (0, b'\xff\x58\x04\x04\x02\x18\x08'),
             (0, b'\xff\x59\x02\xff\x00')]
    tracks = [tempo, piano, keys, mallet, pad, drum]
    data = b'MThd' + struct.pack('>IHHH', 6, 1, len(tracks), TPQ)
    data += b''.join(chunk(part) for part in tracks)
    path = ROOT / 'bucea-focus-original.mid'
    path.write_bytes(data)
    print(f'{path.name}: {BPM} BPM, 16 bars, 42.667 s plus reverb tail')


if __name__ == '__main__':
    main()
