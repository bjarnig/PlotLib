/*
Render the meter and the spectrum from real audio, rather than from frames pushed
in from the language.

	sclang tests/render-live-shots.scd

The synthetic shots in render-shots.scd are reproducible, which is what makes them
useful in tests. These are what the two views actually look like on a signal.
*/

var dir = thisProcess.nowExecutingPath.absolutePath.dirname.dirname +/+ "images/";
var shot;

shot = { |name, view, wait = 1.2|
	var path = dir ++ name ++ ".png";
	view.front;
	wait.wait;
	view.writeImage(path);
	view.close;
	0.3.wait;
	("  wrote " ++ name ++ ".png").postln;
};

Routine({
	var src;

	s.options.numOutputBusChannels = 2;
	s.bootSync;
	0.5.wait;
	("rendering from live audio at " ++ s.sampleRate ++ " Hz").postln;

	// a level meter on a signal that differs between the channels, so the two bars
	// are not copies of each other
	src = {
		[
			SinOsc.ar(220, 0, 0.32) + (PinkNoise.ar(0.05)),
			PinkNoise.ar(0.16) * LFTri.kr(0.7).range(0.4, 1.0)
		]
	}.play;
	shot.value("meter-live", PltMeter(2, 0, s, "output level"), 2.0);

	// a harmonic tone, where the linear axis lets the partials be counted
	src.free;
	0.3.wait;
	src = { Blip.ar(180, 22, 0.3) ! 2 }.play;
	shot.value("spectrum-live",
		PltSpectrum(0, 2048, s, "Blip, 180 Hz, 22 harmonics").maxHz_(5000), 2.0);

	// a resonant sweep on a log axis, where the whole range matters
	src.free;
	0.3.wait;
	src = {
		var noise = PinkNoise.ar(0.5);
		BPF.ar(noise, LFNoise2.kr(0.3).exprange(200, 4000), 0.08, 6) ! 2
	}.play;
	shot.value("spectrum-live-log",
		PltSpectrum(0, 2048, s, "filtered noise").logFreq_(true).minHz_(30).maxHz_(20000),
		2.5);

	src.free;
	0.5.wait;
	"done".postln;
	s.quit;
	0.5.wait;
	0.exit;
}).play(AppClock);
