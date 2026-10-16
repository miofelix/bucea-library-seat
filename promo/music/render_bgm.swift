import AVFoundation
import AudioToolbox
import Foundation

// macOS offline General MIDI rendering.  No audio device is recorded.
let directory = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "promo/music", isDirectory: true)
let midiURL = directory.appendingPathComponent("bucea-focus-original.mid")
let outputURL = directory.appendingPathComponent("bucea-focus-original-raw.wav")
let bankURL = URL(fileURLWithPath: "/System/Library/Components/CoreAudio.component/Contents/Resources/gs_instruments.dls")
let engine = AVAudioEngine()
let rate = 48_000.0
let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2)!
let reverb = AVAudioUnitReverb()
reverb.loadFactoryPreset(.mediumHall)
reverb.wetDryMix = 15
let equalizer = AVAudioUnitEQ(numberOfBands: 2)
equalizer.bands[0].filterType = .highPass
equalizer.bands[0].frequency = 72
equalizer.bands[0].bypass = false
equalizer.bands[1].filterType = .lowPass
equalizer.bands[1].frequency = 11_500
equalizer.bands[1].bypass = false
let instruments: [(UInt8, Bool)] = [(0, false), (4, false), (11, false), (89, false), (0, true)]
var samplers: [AVAudioUnitSampler] = []
engine.attach(reverb)
engine.attach(equalizer)
for (program, percussion) in instruments {
    let sampler = AVAudioUnitSampler()
    engine.attach(sampler)
    try sampler.loadSoundBankInstrument(at: bankURL, program: program,
        bankMSB: UInt8(percussion ? kAUSampler_DefaultPercussionBankMSB : kAUSampler_DefaultMelodicBankMSB),
        bankLSB: UInt8(kAUSampler_DefaultBankLSB))
    engine.connect(sampler, to: engine.mainMixerNode, format: format)
    samplers.append(sampler)
}
engine.connect(engine.mainMixerNode, to: equalizer, format: format)
engine.connect(equalizer, to: reverb, format: format)
engine.connect(reverb, to: engine.outputNode, format: format)
engine.mainMixerNode.outputVolume = 0.72
let sequencer = AVAudioSequencer(audioEngine: engine)
try sequencer.load(from: midiURL, options: [])
// AVAudioSequencer preserves the SMF's meta-only tempo track on macOS.
let musicalTracks = Array(sequencer.tracks.suffix(samplers.count))
guard musicalTracks.count == samplers.count else {
    fatalError("Expected \(samplers.count) musical tracks; received \(musicalTracks.count)")
}
if sequencer.tracks.count > samplers.count { sequencer.tracks[0].isMuted = true }
for (index, track) in musicalTracks.enumerated() {
    track.destinationAudioUnit = samplers[index]
}
try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
try engine.start()
sequencer.prepareToPlay()
try sequencer.start()
let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat,
                              frameCapacity: engine.manualRenderingMaximumFrameCount)!
var output: AVAudioFile? = try AVAudioFile(forWriting: outputURL,
                            settings: engine.manualRenderingFormat.settings)
let desiredFrames = AVAudioFramePosition(45 * rate)
var retries = 0
while engine.manualRenderingSampleTime < desiredFrames {
    let count = AVAudioFrameCount(min(AVAudioFramePosition(buffer.frameCapacity), desiredFrames - engine.manualRenderingSampleTime))
    let status = try engine.renderOffline(count, to: buffer)
    switch status {
    case .success:
        try output!.write(from: buffer)
        retries = 0
    case .insufficientDataFromInputNode, .cannotDoInCurrentContext:
        retries += 1
        if retries > 100 { fatalError("Offline renderer did not progress") }
    case .error:
        fatalError("Offline audio rendering failed")
    @unknown default:
        fatalError("Unknown rendering result")
    }
}
sequencer.stop()
engine.stop()
// Explicitly finalize the WAV header before a Swift interpreter exits.
output = nil
print("Rendered 45 seconds to \(outputURL.path)")
