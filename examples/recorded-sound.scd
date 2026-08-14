/*
Looking at recorded sound: waveform, spectrogram, vectorscope, envelope.

Only the live examples need the server.
*/

~path = Platform.resourceDir +/+ "sounds/a11wlk01.wav";

// The waveform: min and max per pixel column, RMS inside it. A whole file at any
// length, with no peak lost to decimation.
~wave = PltWave.fromSoundFile(~path).front;

// Zoom into a range. This rereads the file, so the detail is real detail.
~wave.zoom(0.8, 1.0);
~wave.zoom(2.05, 2.2);
~wave.zoom(0);

// In dB, where the quiet parts are legible and the distance between the filled
// peak and the RMS line is the crest factor.
~wave.dbScale_(true);
~wave.dbScale_(false);

// A stereo pair, one panel per channel, sharing the time axis
(
PltWave([
	Signal.fill(88200, { |i| sin(i * 0.02) * (1 - (i / 88200)) }),
	Signal.fill(88200, { |i| sin(i * 0.031) * ((i / 88200) ** 2) })
], 44100, "decay and swell").front;
)


// The spectrogram of the same file, offline. Linear frequency first: partials are
// evenly spaced, so a harmonic sound reads as a ladder.
PltSpectrogram.fromSoundFile(~path, 1024).front;

// Logarithmic, where formant movement reads as shapes and the low end opens up
PltSpectrogram.fromSoundFile(~path, 2048).logFreq_(true).front;

// A darker floor, if the noise is in the way
PltSpectrogram.fromSoundFile(~path, 2048).logFreq_(true).gamma_(3.4).front;


// Envelopes, drawn as the shapes they are
PltEnvelope(Env.perc(0.01, 0.4), "Env.perc").front;
PltEnvelope(Env.adsr(0.02, 0.2, 0.4, 0.5), "Env.adsr").front;

// What a curve value does, which is hard to remember and easy to see
(
PltEnvelope(Env([0, 1, 0], [0.5, 0.5], -8), "curve -8").position_(40 @ 500).front;
PltEnvelope(Env([0, 1, 0], [0.5, 0.5], 0), "curve 0").position_(40 @ 180).front;
PltEnvelope(Env([0, 1, 0], [0.5, 0.5], 8), "curve 8").position_(760 @ 500).front;
)


// ---- live ----

s.boot;

// A spectrogram of the output bus
~sg = PltSpectrogram(0).front;
~src = { Blip.ar(LFNoise0.kr(2).exprange(80, 400), 20, 0.2) ! 2 }.play;
~sg.span_(16);
~sg.logFreq_(true);
~src.free;

// A vectorscope. Identical channels draw a vertical line, independent ones a
// cloud, an inverted channel a horizontal line.
~vec = PltVector.live(0).alwaysOnTop;

~src = { PinkNoise.ar(0.2) ! 2 }.play;                       // identical
~src.free;
~src = { [PinkNoise.ar(0.2), PinkNoise.ar(0.2)] }.play;      // independent
~src.free;
~src = { var m = PinkNoise.ar(0.2); [m, m.neg] }.play;       // inverted
~vec.correlation;
~src.free;

(
~sg.close;
~vec.close;
)


// ---- analysis and mapping ----

// What the descriptors do while a sound plays. Onsets tick through every panel.
~tracks = PltTrack.analysis(0).front;
~src = { Blip.ar(LFNoise0.kr(1).exprange(60, 300), 12, 0.2) ! 2 }.play;
~src.free;
~src = { WhiteNoise.ar(0.15) ! 2 }.play;         // flat, high centroid
~src.free;
~tracks.close;

// Tracks of your own, from a pattern
(
~tracks = PltTrack([
	(name: "freq", lo: 100, hi: 1000, unit: "Hz"),
	(name: "amp", lo: 0, hi: 0.5)
], 8, "what the pattern is doing").front;

~seq = Pbind(
	\dur, Pwhite(0.05, 0.2),
	\freq, Pbrown(120, 900, 80),
	\amp, Pwhite(0.05, 0.4)
).collect { |ev| ~tracks.push([ev[\freq], ev[\amp]]); ev }.play;
)
~seq.stop; ~tracks.close;


// A mapping curve: the same range, three warps, with the control at a quarter
(
~map = PltMap(\freq.asSpec, "exp", "the same range, three warps");
~map.add(ControlSpec(20, 20000, \lin), "lin");
~map.add(ControlSpec(20, 20000, 4), "curve 4");
~map.marker_(0.25).front;
)

// Drag the control and watch all three at once
(
Routine({
	(0, 0.005 .. 1).do { |t| ~map.marker_(t); 0.03.wait };
}).play(AppClock);
)

// On a log output axis the exponential mapping is the straight line
~map.logOutput_(true);
~map.close;
