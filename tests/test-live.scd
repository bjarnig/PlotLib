/*
Tests for the two views that read from the server: PltMeter and PltSpectrum.

	sclang tests/test-live.scd

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
	ok.value("silence decays toward the floor", {
		1.5.wait;
		PlotLib.ampDb(spectrum.mags.maxItem) < -40
	}.value);

	spectrum.close;
	0.5.wait;

	"".postln;
	("checks: " ++ checks ++ ", failures: " ++ fails).postln;
	if(fails > 0) { "*** FAILURES ***".postln } { "all live tests passed".postln };
	s.quit;
	0.5.wait;
	0.exit;
}).play(AppClock);
