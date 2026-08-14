/*
Tests for the two views that read from the server: PltMeter and PltSpectrum.

	sclang tests/test-live.scd

Run this one on its own. It boots the default server and takes the audio device, so
a second sclang instance or an SC IDE with a server running will make it fail in a
way that looks like a bug in the views: both freeze partway and keep reporting the
previous tone.

Both are asserted against signals whose level and frequency are known exactly.
The spectrum tones sit on bin centres (bin * sr / fftSize), so the peak bin must
come back as that bin and not a neighbour: an off-by-one in the packed FFT frame
would show up here and nowhere else.
*/

var fails = 0, checks = 0, ok, near;

ok = { |name, cond|
	checks = checks + 1;
	if(cond) { ("  ok    " ++ name).postln } {
		fails = fails + 1;
		("  FAIL  " ++ name).postln;
	};
};
near = { |a, b, tol| (a - b).abs <= tol };

Routine({
	var meter, spectrum, source, sr, binHz;

	s.options.numOutputBusChannels = 2;
	s.bootSync;
	sr = s.sampleRate;
	binHz = sr / 1024;
	("server up at " ++ sr ++ " Hz, one bin is " ++ binHz.round(0.01) ++ " Hz").postln;
	0.5.wait;

	"--- PltMeter, against a sine of known amplitude ---".postln;
	meter = PltMeter(2, 0, s, "meter test");
	source = { SinOsc.ar(440, 0, 0.5) ! 2 }.play;
	1.5.wait;

	// a sine at 0.5 has peak 0.5 and RMS 0.5/sqrt(2)
	ok.value("peak follows the signal: " ++ meter.peaks.collect(_.round(0.001)),
		meter.peaks.every { |p| near.value(p, 0.5, 0.02) });
	ok.value("rms follows the signal: " ++ meter.rmss.collect(_.round(0.001)),
		meter.rmss.every { |r| near.value(r, 0.5 / sqrt(2), 0.03) });
	ok.value("both channels are metered separately", meter.peaks.size == 2);
	ok.value("nothing is reported as clipping", meter.clipping.not);

	source.free;
	0.6.wait;
	ok.value("rms falls to silence at once when the source stops: "
		++ meter.rmss.collect(_.round(0.0001)),
		meter.rmss.every { |r| r < 0.001 });
	// with peakLag left at SendPeakRMS's default of 3 s this would still read 0.35
	ok.value("the peak line follows within a fraction of a second: "
		++ meter.peaks.collect(_.round(0.0001)),
		meter.peaks.every { |p| p < 0.05 });
	meter.close;
	0.3.wait;

	"--- PltSpectrum, tones on exact bin centres ---".postln;
	spectrum = PltSpectrum(0, 1024, s, "spectrum test");
	0.5.wait;

	[8, 100, 400].do { |bin|
		var freq = bin * sr / 1024;
		var syn = { SinOsc.ar(freq, 0, 0.5) ! 2 }.play;
		var peakBin, peakDb;
		1.2.wait;
		peakBin = spectrum.mags.maxIndex;
		peakDb = PlotLib.ampDb(spectrum.mags[peakBin]);
		ok.value("a tone on bin " ++ bin ++ " (" ++ freq.round(0.1)
			++ " Hz) peaks at bin " ++ peakBin, peakBin == bin);
		// 0.5 amplitude is -6 dBFS; the normalisation should land there
		ok.value("its level reads " ++ peakDb.round(0.1) ++ " dB, expected about -6",
			near.value(peakDb, -6.0, 3.0));
		syn.free;
		0.6.wait;
	};

	ok.value("bin count is fftSize/2 + 1", spectrum.mags.size == 513);

	/*
	cmd-period frees every node and clears the clocks, which takes the analysis
	synth and the poller with it. Both views register a restart with ServerTree,
	which fires just after. Without it they keep displaying the last frame they
	received for ever, and a frozen meter looks exactly like a working one.

	The check needs a source that differs from the first in both level and
	frequency, or a stale reading passes.
	*/
	"--- recovery after cmd-period ---".postln;
	meter = PltMeter(2, 0, s, "recovery");
	0.5.wait;
	source = { SinOsc.ar(8 * sr / 1024, 0, 0.5) ! 2 }.play;
	1.2.wait;
	ok.value("both views are reading the first source",
		near.value(meter.peaks[0], 0.5, 0.02) and: { spectrum.mags.maxIndex == 8 });

	CmdPeriod.run;
	1.0.wait;
	source = { SinOsc.ar(100 * sr / 1024, 0, 0.25) ! 2 }.play;
	2.0.wait;
	ok.value("the meter recovers, reading " ++ meter.peaks[0].round(0.001)
		++ " where a frozen one would still read 0.5",
		near.value(meter.peaks[0], 0.25, 0.02));
	ok.value("the spectrum recovers, peaking at bin " ++ spectrum.mags.maxIndex
		++ " where a frozen one would still read 8",
		spectrum.mags.maxIndex == 100);
	source.free;
	meter.close;
	0.3.wait;
	ok.value("silence decays toward the floor", {
		1.5.wait;
		PlotLib.ampDb(spectrum.mags.maxItem) < -40
	}.value);

	spectrum.close;
	0.5.wait;

	/*
	PltTrack's built in analysis. Asserted as what the descriptors mean, not as
	"numbers arrived": noise is spectrally flat and a sine is not, and a sine's
	centroid sits at its own frequency.
	*/
	"--- PltTrack.analysis, against signals whose descriptors are known ---".postln;
	{
		var track = PltTrack.analysis(0, s, 10, "analysis test");
		var valueOf = { |name|
			var t = track.tracks.detect { |each| each[\name] == name };
			var last = t[\points].last;
			if(last.isNil) { nil } { last[1] }
		};
		var sineFlat, noiseFlat, sineCentroid;
		0.5.wait;

		source = { SinOsc.ar(500, 0, 0.5) ! 2 }.play;
		2.0.wait;
		sineFlat = valueOf.value("flatness");
		sineCentroid = valueOf.value("centroid");
		ok.value("a sine's loudness is reported at all: " ++ valueOf.value("loudness"),
			valueOf.value("loudness").notNil and: { valueOf.value("loudness") > 0 });
		ok.value("a 500 Hz sine has its centroid near 500: " ++ sineCentroid.round(1),
			near.value(sineCentroid, 500, 250));
		source.free;
		0.5.wait;

		source = { WhiteNoise.ar(0.3) ! 2 }.play;
		2.0.wait;
		noiseFlat = valueOf.value("flatness");
		ok.value("noise is flatter than a sine: " ++ noiseFlat.round(0.001)
			++ " against " ++ sineFlat.round(0.001), noiseFlat > (sineFlat * 3));
		ok.value("noise has a much higher centroid than the sine: "
			++ valueOf.value("centroid").round(1),
			valueOf.value("centroid") > (sineCentroid * 3));
		source.free;
		0.5.wait;

		/*
		Onsets: a train of percussive hits. Played from a Routine with a gateless
		synth that frees itself, rather than from a pattern: stopping a pattern
		mid-note leaves /n_set messages addressed to nodes that have already gone,
		and "Node not found" in a test log reads like a fault.
		*/
		track.reset;
		SynthDef(\plotlib_test_hit, { |freq = 400, amp = 0.4|
			var env = EnvGen.ar(Env.perc(0.001, 0.12), doneAction: 2);
			OffsetOut.ar(0, (SinOsc.ar(freq) * env * amp) ! 2);
		}).add;
		s.sync;
		source = Routine({ 12.do { Synth(\plotlib_test_hit); 0.25.wait } }).play;
		3.2.wait;
		ok.value("onsets were detected: " ++ track.marks.size ++ " marks for 12 hits",
			track.marks.size > 6);
		source.stop;
		track.close;
		0.5.wait;
	}.value;

	"".postln;
	("checks: " ++ checks ++ ", failures: " ++ fails).postln;
	if(fails > 0) { "*** FAILURES ***".postln } { "all live tests passed".postln };
	s.quit;
	0.5.wait;
	0.exit;
}).play(AppClock);
