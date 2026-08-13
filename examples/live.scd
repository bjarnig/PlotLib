/*
The live views: what a pattern is playing, and what the output bus is doing.

Boot the server first.
*/

s.boot;

// A meter and a spectrum on the first output channels
(
~meter = PltMeter(2, 0, s, "output").front;
~spec = PltSpectrum(0, 1024, s, "output").front;
)

~noise = { PinkNoise.ar(0.2) ! 2 }.play;
~noise.free;

// A harmonic tone, to count partials against the linear frequency axis
~tone = { Blip.ar(120, 24, 0.3) ! 2 }.play;
~spec.maxHz_(4000);      // fill the panel at this pitch
~tone.free;

// The whole range instead, labelled where a listener thinks
~spec.logFreq_(true).minHz_(30).maxHz_(20000);
~spec.smooth_(0.85);     // slower and steadier
~spec.smooth_(0.5);

// Deliberate clipping, to see the meter mark it
~loud = { SinOsc.ar(200, 0, 1.4) ! 2 }.play;
~meter.clipping;
~loud.free;

(
~meter.close;
~spec.close;
)


// An event plotter, following a pattern
(
SynthDef(\plt_perc, { |freq = 440, amp = 0.1|
	var env = EnvGen.ar(Env.perc(0.005, 0.25), 1, doneAction: 2) * amp;
	OffsetOut.ar(0, SinOsc.ar(freq, 0, env) ! 2);
}).add;
)

(
~ev = PltEvents("Pwhite", 8, 150, 1200).front;
~seq = ~ev.watch(
	Pbind(
		\instrument, \plt_perc,
		\dur, Pwhite(0.08, 0.3),
		\freq, Pwhite(200, 900),
		\amp, Pwhite(0.1, 0.4)
	)
).play;
)

~seq.stop;

// Degrees rather than frequencies. The frequency axis makes the scale visible,
// and the histogram below shows which pitches are actually being used.
(
~ev.reset;
~seq = ~ev.watch(
	Pbind(
		\instrument, \plt_perc,
		\dur, 0.12,
		\degree, Pwhite(-7, 14),
		\amp, 0.2
	)
).play;
)

~seq.stop;

// An exponential distribution of frequencies, which the histogram makes plain
(
~ev.reset;
~seq = ~ev.watch(
	Pbind(
		\instrument, \plt_perc,
		\dur, 0.06,
		\freq, Pexprand(160, 1100),
		\amp, 0.15
	)
).play;
)

(
~seq.stop;
~ev.close;
)


// All three at once, which is the point of them being separate windows
(
~ev = PltEvents("events", 8, 150, 1200, 40, 700, 380).front;
~meter = PltMeter(2, 0, s, "level", 240, 300).front;
~spec = PltSpectrum(0, 1024, s, "spectrum", 700, 320).front;
~seq = ~ev.watch(
	Pbind(
		\instrument, \plt_perc,
		\dur, Pwhite(0.05, 0.25),
		\freq, Pexprand(180, 1200),
		\amp, Pwhite(0.1, 0.3)
	)
).play;
)

(
~seq.stop;
~ev.close; ~meter.close; ~spec.close;
)
