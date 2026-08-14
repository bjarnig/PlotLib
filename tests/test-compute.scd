/*
Headless tests for everything in PlotLib that computes rather than draws.

	sclang tests/test-compute.scd

Identity cases first: where the answer is known exactly, assert it exactly.
Attractor and distribution claims are asserted as the property they stand for,
not as "it ran".

No outer parentheses and no var declarations inside a parenthesised group: in
file mode the whole file is one function body, and `( var x = ... )` does not
parse. Grouped locals go in { ... }.value instead.
*/

var fails = 0, checks = 0, ok, near, logistic, henon;

ok = { |name, cond|
	checks = checks + 1;
	if(cond) { ("  ok    " ++ name).postln } {
		fails = fails + 1;
		("  FAIL  " ++ name).postln;
	};
};
near = { |a, b, tol = 1e-9| (a - b).abs <= tol };

"--- PltFilter: responses with known answers ---".postln;
ok.value("a passthrough is 0 dB and 0 phase everywhere", {
	var r = PltFilter.biquadResponse([1, 0, 0, 0, 0], [1, 1000, 12000, 23999], 48000);
	r[0].every { |m| near.value(m, 1) } and: { r[1].every { |p| near.value(p, 0) } }
}.value);
ok.value("a one sample delay is flat, and -pi at nyquist", {
	var r = PltFilter.biquadResponse([0, 1, 0, 0, 0], [1, 1000, 23999.99], 48000);
	r[0].every { |m| near.value(m, 1) } and: { near.value(r[1].last, pi.neg, 1e-4) }
}.value);
ok.value("a two point averager is 1, 1/sqrt(2) and 0", {
	var r = PltFilter.biquadResponse([0.5, 0.5, 0, 0, 0], [0.0001, 12000, 23999.999], 48000);
	near.value(r[0][0], 1, 1e-6) and: { near.value(r[0][1], 1 / sqrt(2), 1e-4) }
		and: { near.value(r[0][2], 0, 1e-4) }
}.value);
ok.value("a difference filter is a null at dc", {
	near.value(PltFilter.biquadResponse([1, -1, 0, 0, 0], [0.0001], 48000)[0][0], 0, 1e-6)
}.value);
// two independent paths to the same answer: the transfer function evaluated
// directly, and a DFT of the impulse response the same coefficients produce
ok.value("the impulse response path agrees with the coefficients", {
	var c = [0.2, 0.1, 0, 0.5, -0.1];
	var ir = { var x1 = 0, x2 = 0, y1 = 0, y2 = 0;
		Array.fill(2048, { |i|
			var x = if(i == 0) { 1 } { 0 };
			var y = (c[0] * x) + (c[1] * x1) + (c[2] * x2) + (c[3] * y1) + (c[4] * y2);
			x2 = x1; x1 = x; y2 = y1; y1 = y;
			y
		})
	}.value;
	var probe = [500, 2000, 8000];
	var direct = PltFilter.biquadResponse(c, probe, 48000)[0];
	var measured = PltFilter.impulseResponse(ir, probe, 48000)[0];
	3.collect { |i| near.value(direct[i], measured[i], 1e-4) }.every { |b| b }
}.value);
ok.value("log spaced frequencies span the range", {
	var f = PltFilter.logFreqs(20, 20000, 100);
	near.value(f.first, 20, 1e-9) and: { near.value(f.last, 20000, 1e-6) }
		and: { f == f.sort }
}.value);

"--- PltDistribution ---".postln;
ok.value("expected counts sum to the sample total", {
	var e = PltDistribution.expectedCounts(PltDistribution.density(\uniform), 0, 1, 10, 1000);
	near.value(e.sum, 1000, 1e-6) and: { e.every { |v| near.value(v, 100, 1e-6) } }
}.value);
/*
The density is averaged across each bin rather than read at its centre: a centre
sample put 7000 where 3950 samples had actually landed. subSamples of 1 is exactly
the centre sample, so the two methods can be compared directly. 1/x is convex, so
its mean over a bin is greater than its value at the middle.
*/
ok.value("a steep density is integrated across the bin, not sampled at its centre", {
	var density = PltDistribution.density(\logUniform);
	var centre = PltDistribution.expectedCounts(density, 0.01, 1, 8, 1000, 1);
	var integrated = PltDistribution.expectedCounts(density, 0.01, 1, 8, 1000, 16);
	(integrated.first > centre.first)
		and: { integrated == integrated.reverse.sort.reverse }
}.value);
ok.value("deviation is 0 for a match and 1 for the opposite", {
	near.value(PltDistribution.deviation([10, 20, 30], [10, 20, 30]), 0)
		and: { near.value(PltDistribution.deviation([60, 0, 0], [0, 0, 60]), 1, 1e-9) }
}.value);
// exprand is log-uniform, not exponential, whatever the name suggests
ok.value("exprand matches a 1/x density and not a flat one", {
	var values = { exprand(0.01, 1.0) } ! 20000;
	var counts = PlotLib.histogram(values, 0.01, 1, 40);
	var logU = PltDistribution.expectedCounts(
		PltDistribution.density(\logUniform), 0.01, 1, 40, 20000);
	var flat = PltDistribution.expectedCounts(
		PltDistribution.density(\uniform), 0.01, 1, 40, 20000);
	(PltDistribution.deviation(counts, logU) < 0.02)
		and: { PltDistribution.deviation(counts, flat) > 0.05 }
}.value);
ok.value("rand matches a flat density", {
	var counts = PlotLib.histogram({ 1.0.rand } ! 20000, 0, 1, 40);
	PltDistribution.deviation(counts,
		PltDistribution.expectedCounts(PltDistribution.density(\uniform),
			0, 1, 40, 20000)) < 0.01
}.value);
ok.value("an unknown density name warns and falls back to flat", {
	PltDistribution.density(\nosuchdensity).value(0.5) == 1
}.value);

"--- PltMarkov ---".postln;
ok.value("rows are normalised to 1", {
	PltMarkov.normalise([[9, 1], [5, 5]]) == [[0.9, 0.1], [0.5, 0.5]]
}.value);
// a dead end row is left at zero: spreading it would invent transitions
ok.value("a row of zeros stays zero", PltMarkov.normalise([[1, 1], [0, 0]])[1] == [0, 0]);
ok.value("the stationary distribution of a known chain is 5/6 and 1/6", {
	var p = PltMarkov.stationary([[0.9, 0.1], [0.5, 0.5]]);
	near.value(p[0], 5/6, 1e-6) and: { near.value(p[1], 1/6, 1e-6) }
}.value);
ok.value("a doubly stochastic chain is uniform", {
	PltMarkov.stationary([[0.5, 0.5], [0.5, 0.5]]).every { |v| near.value(v, 0.5, 1e-9) }
}.value);
ok.value("entropy is 0 bits when deterministic and 1 when a coin toss", {
	var e = PltMarkov.entropy([[1, 0], [0.5, 0.5]]);
	near.value(e[0], 0, 1e-9) and: { near.value(e[1], 1, 1e-9) }
}.value);
ok.value("transitions are counted from a sequence", {
	var m = PltMarkov.count([\a, \a, \b, \a, \b], [\a, \b]);
	near.value(m[0][0], 1/3, 1e-9) and: { near.value(m[0][1], 2/3, 1e-9) }
		and: { near.value(m[1][0], 1, 1e-9) } and: { near.value(m[1][1], 0, 1e-9) }
}.value);
ok.value("states come back in order of first appearance",
	PltMarkov.statesOf([\c, \a, \c, \b]) == [\c, \a, \b]);

"--- PltMap.points ---".postln;
// the endpoints and the exponential middle are exact, known answers
ok.value("an exponential spec hits both ends and the geometric middle", {
	var p = PltMap.points(\freq.asSpec, 101);
	near.value(p[1].first, 20, 1e-6)
		and: { near.value(p[1].last, 20000, 1e-6) }
		and: { near.value(p[1][50], sqrt(20 * 20000), 0.01) }
}.value);
ok.value("a linear spec gives the arithmetic middle", {
	var p = PltMap.points(ControlSpec(20, 20000, \lin), 101);
	near.value(p[1][50], (20 + 20000) / 2, 1e-6)
}.value);
ok.value("inputs run from 0 to 1", {
	var p = PltMap.points(\amp.asSpec, 50);
	near.value(p[0].first, 0) and: { near.value(p[0].last, 1) }
		and: { p[0] == p[0].sort }
}.value);
// a ControlSpec, a Warp and a Function all have to go through one door
ok.value("a Warp maps like the spec it came from", {
	near.value(PltMap.mapWith(ExponentialWarp(ControlSpec(20, 20000, \exp)), 0.5),
		sqrt(20 * 20000), 0.01)
}.value);
ok.value("a Function is called with the control value",
	near.value(PltMap.mapWith({ |x| x * x }, 0.25), 0.0625, 1e-9));
ok.value("a Symbol resolves to its spec", PltMap.asSpec(\amp).isKindOf(ControlSpec));
ok.value("a curve spec sits between linear and exponential at the same input", {
	var lin = PltMap.mapWith(ControlSpec(20, 20000, \lin), 0.25);
	var curved = PltMap.mapWith(ControlSpec(20, 20000, 4), 0.25);
	var exp = PltMap.mapWith(ControlSpec(20, 20000, \exp), 0.25);
	(exp < curved) and: { curved < lin }
}.value);

"--- PltWave.envelope ---".postln;
ok.value("a full scale sine gives the right envelope and RMS", {
	var sig = Signal.fill(4096, { |i| sin(i * 2pi / 64) });
	var e = PltWave.envelope(sig, 100);
	near.value(e[\mins].minItem, -1, 1e-4)
		and: { near.value(e[\maxs].maxItem, 1, 1e-4) }
		and: { near.value(e[\rms].mean, 1 / sqrt(2), 0.01) }
}.value);
// the whole point of min/max per column: decimation must not lose a transient
ok.value("a single spike survives 200 to 1 decimation", {
	var sig = Signal.fill(100000, { 0 });
	var e;
	sig[54321] = 0.9;
	e = PltWave.envelope(sig, 500);
	near.value(e[\maxs].maxItem, 0.9, 1e-4)
}.value);
ok.value("never more columns than samples",
	PltWave.envelope(Signal.fill(10, { 0.5 }), 900)[\mins].size == 10);
ok.value("an empty signal does not raise", PltWave.envelope([], 100)[\mins].size > 0);
ok.value("silence gives a flat envelope", {
	var e = PltWave.envelope(0 ! 1000, 50);
	e[\mins].every { |v| v == 0 } and: { e[\rms].every { |v| v == 0 } }
}.value);

"--- PltEnvelope.points ---".postln;
// sampling evenly cuts corners: Env.adsr came back with a peak of 0.965
ok.value("every Env type is sampled at its exact peak", {
	[Env.adsr(0.02, 0.2, 0.4, 0.5), Env.perc(0.01, 0.4), Env.triangle(1, 0.8),
		Env.sine(1, 0.6), Env.linen(0.1, 0.2, 0.3, 0.7),
		Env([0.01, 1, 0.01], [0.05, 0.5], \exp)].every { |env|
		var p = PltEnvelope.points(env, 50);
		near.value(p[1].maxItem, env.levels.maxItem, 1e-9)
	}
}.value);
ok.value("times are sorted and inside the duration", {
	var env = Env.adsr(0.02, 0.2, 0.4, 0.5);
	var p = PltEnvelope.points(env, 40);
	(p[0] == p[0].sort) and: { p[0].last <= (env.duration + 1e-9) }
		and: { p[0].first >= 0 }
}.value);

"--- PltSpectrogram.column ---".postln;
ok.value("a loud bin lands on the row its frequency belongs to", {
	var frame = 0 ! 513;
	var col, want;
	frame[100] = 1.0;                                  // 4687.5 Hz at 48k, 1024
	col = PltSpectrogram.column(frame, 100, 40, 12000, -78, 0, false, 48000, 1024);
	want = (100 - (4687.5 / 12000 * 100)).round(1).asInteger;
	(col.maxIndex - want).abs <= 1
}.value);
ok.value("the log axis puts it on the log row", {
	var frame = 0 ! 513;
	var col, want;
	frame[100] = 1.0;
	col = PltSpectrogram.column(frame, 100, 40, 12000, -78, 0, true, 48000, 1024);
	want = (100 - ((4687.5.log10 - 40.log10) / (12000.log10 - 40.log10) * 100))
		.round(1).asInteger;
	(col.maxIndex - want).abs <= 1
}.value);
ok.value("silence is 0 and full scale is 1", {
	(PltSpectrogram.column(0 ! 513, 20).maxItem == 0)
		and: { near.value(PltSpectrogram.column(1 ! 513, 20).maxItem, 1, 1e-9) }
}.value);
ok.value("row 0 is the top of the picture, so it is the high end", {
	var lowFrame = 0 ! 513, highFrame = 0 ! 513, lowCol, highCol;
	lowFrame[5] = 1.0; highFrame[200] = 1.0;
	lowCol = PltSpectrogram.column(lowFrame, 60);
	highCol = PltSpectrogram.column(highFrame, 60);
	highCol.maxIndex < lowCol.maxIndex
}.value);
// a log axis has many rows per bin at the low end, and repeating the bin drew
// them as horizontal bands
ok.value("sub-bin rows interpolate instead of repeating", {
	var frame = 0 ! 513, col, lowRows;
	5.do { |i| frame[i + 1] = (i + 1) / 5 };
	col = PltSpectrogram.column(frame, 200, 30, 20000, -78, 0, true, 48000, 1024);
	lowRows = col.copyRange(150, 199);
	lowRows.asSet.size > 10
}.value);

"--- PltVector.correlation ---".postln;
ok.value("identical channels correlate at 1", {
	var a = Array.fill(500, { |i| sin(i * 0.05) });
	near.value(PltVector.correlation(a, a), 1, 1e-9)
}.value);
ok.value("inverted channels correlate at -1", {
	var a = Array.fill(500, { |i| sin(i * 0.05) });
	near.value(PltVector.correlation(a, a.neg), -1, 1e-9)
}.value);
ok.value("correlation ignores level", {
	var a = Array.fill(500, { |i| sin(i * 0.05) });
	near.value(PltVector.correlation(a, a * 0.25), 1, 1e-9)
}.value);
ok.value("quadrature is uncorrelated", {
	var a = Array.fill(2000, { |i| sin(i * 0.05) });
	var b = Array.fill(2000, { |i| cos(i * 0.05) });
	PltVector.correlation(a, b).abs < 0.05
}.value);
ok.value("silence gives 0 rather than nan", PltVector.correlation(0 ! 100, 0 ! 100) == 0);
ok.value("empty gives 0", PltVector.correlation([], []) == 0);

"--- PltMeter.widthFor ---".postln;
ok.value("two margins plus a bar and a gap per channel",
	PltMeter.widthFor(2) == (46 + 14 + (2 * (PltMeter.barWidth + PltMeter.barGap))));
// every passes the item and its index, and nothing else: a third argument is nil
ok.value("wider with more channels", {
	var widths = [1, 2, 4, 8, 16].collect { |n| PltMeter.widthFor(n) };
	widths.every { |w, i| (i == 0) or: { w > widths[i - 1] } }
}.value);
ok.value("zero channels does not give a negative width", PltMeter.widthFor(0) > 60);

"--- PlotLib themes ---".postln;
ok.value("the default theme is darkBlue", PlotLib.theme == \darkBlue);
ok.value("three themes are built in", [\darkBlue, \dark, \light].every { |t|
	PlotLib.themes[t].notNil });
ok.value("every theme defines every key", {
	var keys = [\ground, \bg, \ink, \mint, \gold, \rose, \trace, \muted, \edge, \grid];
	PlotLib.themes.keys.every { |name|
		keys.every { |k| PlotLib.themes[name][k].notNil }
	}
}.value);
ok.value("a dark ground leaves alpha and line width alone",
	(PlotLib.isLight.not)
		and: { PlotLib.alphaFor(0.35) == 0.35 }
		and: { PlotLib.lineWidth(1) == 1 });
ok.value("switching theme changes the palette", {
	var before = PlotLib.color(\bg);
	PlotLib.theme_(\light);
	PlotLib.color(\bg) != before
}.value);
// a light ground needs more alpha and heavier hairlines, or filled areas wash
// out and 1px lines vanish
ok.value("a light ground boosts alpha", PlotLib.alphaFor(0.35) > 0.5);
ok.value("a light ground thickens hairlines", PlotLib.lineWidth(1) > 1.5);
ok.value("alpha never exceeds 1", PlotLib.alphaFor(0.9) <= 1);
ok.value("an unknown theme is refused and the current one stays", {
	PlotLib.theme_(\nosuchtheme);
	PlotLib.theme == \light
}.value);
PlotLib.theme_(\darkBlue);
ok.value("switching back restores the default", PlotLib.theme == \darkBlue
	and: { PlotLib.isLight.not });

"--- PlotLib.map ---".postln;
ok.value("identity", near.value(PlotLib.map(0.5, 0, 1, 0, 1), 0.5));
ok.value("rescale", near.value(PlotLib.map(5, 0, 10, 100, 200), 150));
ok.value("inverted target range", near.value(PlotLib.map(0, 0, 1, 400, 0), 400));
ok.value("clips above", near.value(PlotLib.map(9, 0, 1, 0, 10), 10));
ok.value("clips below", near.value(PlotLib.map(-9, 0, 1, 0, 10), 0));
ok.value("degenerate input range does not divide by zero",
	near.value(PlotLib.map(3, 2, 2, 0, 10), 0));

"--- PlotLib.histogram ---".postln;
ok.value("counts sum to the input size",
	PlotLib.histogram((0, 0.01 .. 0.99), 0, 1, 10).sum == 100);
ok.value("exact bins", PlotLib.histogram([0.05, 0.15, 0.15, 0.95], 0, 1, 10)
	== [1, 2, 0, 0, 0, 0, 0, 0, 0, 1]);
ok.value("hi lands in the last bin rather than out of range",
	PlotLib.histogram([1.0], 0, 1, 4) == [0, 0, 0, 1]);
ok.value("NaN is skipped", PlotLib.histogram([0.5, sqrt(-1)], 0, 1, 2).sum == 1);
ok.value("uniform input is flat within 4 sigma", {
	var n = 40000, bins = 20, counts, expect, sigma;
	counts = PlotLib.histogram({ 1.0.rand } ! n, 0, 1, bins);
	expect = n / bins;
	sigma = sqrt(expect);
	counts.every { |c| (c - expect).abs < (4 * sigma) }
}.value);

"--- PlotLib.extent ---".postln;
ok.value("min and max", PlotLib.extent([3, -1, 7, 2]) == [-1, 7]);
ok.value("empty is nil", PlotLib.extent([]).isNil);
ok.value("all NaN is nil", PlotLib.extent([sqrt(-1)]).isNil);
ok.value("constant input is widened, never a zero span", {
	var e = PlotLib.extent([5, 5, 5]);
	e[1] > e[0]
}.value);

"--- PlotLib.ticks ---".postln;
ok.value("unit range gives fifths", {
	var t = PlotLib.ticks(0, 1, 6);
	t.size == 6 and: { t.every { |v, i| near.value(v, i * 0.2, 1e-9) } }
}.value);
ok.value("every tick is inside the range", {
	[[2.4, 4.0], [-1.2, 0.37], [0, 22050], [0.001, 0.004], [-7, 7]].every { |r|
		PlotLib.ticks(r[0], r[1], 6).every { |t|
			t >= (r[0] - 1e-9) and: { t <= (r[1] + 1e-9) }
		}
	}
}.value);
ok.value("count is near what was asked", {
	[[2.4, 4.0], [0, 22050], [-1.2, 0.37], [0.001, 0.004]].every { |r|
		var n = PlotLib.ticks(r[0], r[1], 6).size;
		n >= 3 and: { n <= 9 }
	}
}.value);
ok.value("steps are 1, 2 or 5 times a power of ten", {
	[[2.4, 4.0], [0, 1000], [0.001, 0.004], [-7, 7]].every { |r|
		var t = PlotLib.ticks(r[0], r[1], 6), step, norm;
		step = t[1] - t[0];
		norm = step / (10 ** step.log10.floor);
		[1, 2, 5, 10].any { |v| (norm - v).abs < 1e-6 }
	}
}.value);
ok.value("degenerate range does not hang", PlotLib.ticks(1, 1, 6).size == 1);

"--- PlotLib.fmt ---".postln;
ok.value("a whole number loses its decimal", PlotLib.fmt(30.0, 31) == "30");
ok.value("a frequency reads as an integer", PlotLib.fmt(5000, 22050) == "5000");
ok.value("zero is zero", PlotLib.fmt(0.0, 1) == "0");
ok.value("a fraction keeps the precision of its span", PlotLib.fmt(0.25, 1) == "0.25");
ok.value("a small span keeps three places", PlotLib.fmt(0.0025, 0.004) == "0.003");
ok.value("negatives keep their sign", PlotLib.fmt(-20.0, 60) == "-20");

"--- PlotLib.ampDb ---".postln;
ok.value("unity is 0 dB", near.value(PlotLib.ampDb(1.0), 0, 1e-6));
ok.value("half is about -6 dB", near.value(PlotLib.ampDb(0.5), -6.0206, 1e-3));
ok.value("silence hits the floor rather than -inf", PlotLib.ampDb(0, -60) == -60);

"--- PltBifurcation.points: the logistic map ---".postln;
logistic = { |x, r| r * x * (1 - x) };

ok.value("r = 2.5 settles on the fixed point 0.6", {
	var pts = PltBifurcation.points(logistic, 2.5, 2.5, 0.5, 1, 400, 20);
	pts[1].every { |x| near.value(x, 0.6, 1e-6) }
}.value);

[[2.5, 1], [3.2, 2], [3.5, 4]].do { |pair|
	var r = pair[0], want = pair[1], distinct;
	distinct = PltBifurcation.points(logistic, r, r, 0.5, 1, 2000, 64)
		[1].collect(_.round(1e-5)).asSet.size;
	ok.value("r = " ++ r ++ " has period " ++ want ++ ", found " ++ distinct,
		distinct == want);
};

ok.value("r = 3.99 is chaotic and stays inside [0, 1]", {
	var p = PltBifurcation.points(logistic, 3.99, 3.99, 0.5, 1, 500, 200);
	p[1].collect(_.round(1e-5)).asSet.size > 150
		and: { p[1].every { |x| x >= 0 and: { x <= 1 } } }
}.value);

// points come back as FloatArray, which is single precision, so 2.4 reads back
// as 2.40000009: fine for pixels, but never compare it at 1e-9
ok.value("parameters span the whole sweep, one point per column per iteration", {
	var p = PltBifurcation.points(logistic, 2.4, 4.0, 0.5, 100, 100, 10);
	near.value(p[0].minItem, 2.4, 1e-6) and: { near.value(p[0].maxItem, 4.0, 1e-6) }
		and: { p[0].size == (100 * 10) }
}.value);

ok.value("a divergent map yields no points", {
	PltBifurcation.points({ |x, r| x * r * 10 }, 2, 4, 0.5, 20, 200, 10)[1].size == 0
}.value);

"--- PltPhase.points ---".postln;
henon = { |x, y| [1 - (1.4 * x * x) + y, 0.3 * x] };

ok.value("Henon keeps every point and stays bounded", {
	var pts = PltPhase.points(henon, 0.1, 0.1, 400, 3000);
	pts[0].size == 3000
		and: { pts[0].every { |x| x.abs < 2 } }
		and: { pts[1].every { |y| y.abs < 0.6 } }
		and: { pts[0].collect(_.round(1e-6)).asSet.size > 1000 }
}.value);

ok.value("an escaping orbit stops early instead of plotting nonsense", {
	var esc = PltPhase.points({ |x, y| [x * 3, y * 3] }, 1, 1, 0, 1000);
	esc[0].size > 0 and: { esc[0].size < 100 }
		and: { esc[0].every { |x| x.abs <= 1e6 } }
}.value);

ok.value("gingerbreadman is bounded and non-trivial", {
	var g = PltPhase.points({ |x, y| [1 - y + x.abs, x] }, -0.1, 0, 400, 2000);
	g[0].size == 2000 and: { g[0].every { |x| x.abs < 20 } }
		and: { g[0].collect(_.round(1e-6)).asSet.size > 500 }
}.value);

"--- PltSpectrum.magnitudes: the packed FFT frame ---".postln;
// scsynth packs a frame as [DC, nyquist, re1, im1, re2, im2, ...]
ok.value("a hand packed frame unpacks exactly", {
	var d = 0.0 ! 16, mags;
	d[0] = 0.5;                        // DC
	d[1] = 0.25;                       // nyquist
	d[3 * 2] = 3; d[(3 * 2) + 1] = 4;  // bin 3, magnitude 5
	mags = PltSpectrum.magnitudes(FloatArray.newFrom(d));
	mags.size == 9
		and: { near.value(mags[0], 0.5, 1e-6) }
		and: { near.value(mags[8], 0.25, 1e-6) }
		and: { near.value(mags[3], 5.0, 1e-6) }
		and: { [1, 2, 4, 5, 6, 7].every { |i| near.value(mags[i], 0, 1e-12) } }
}.value);

ok.value("negative real and imaginary parts do not cancel", {
	var d = 0.0 ! 16;
	d[2 * 2] = -3; d[(2 * 2) + 1] = -4;
	near.value(PltSpectrum.magnitudes(FloatArray.newFrom(d))[2], 5.0, 1e-6)
}.value);

"--- PltSpectrum.binFreq ---".postln;
ok.value("bin 0 is DC", PltSpectrum.binFreq(0, 1024, 48000) == 0);
ok.value("the nyquist bin is half the sample rate",
	near.value(PltSpectrum.binFreq(512, 1024, 48000), 24000));
ok.value("bin spacing is sr/n",
	near.value(PltSpectrum.binFreq(1, 1024, 48000), 46.875));

"".postln;
("checks: " ++ checks ++ ", failures: " ++ fails).postln;
if(fails > 0) { "*** FAILURES ***".postln } { "all compute tests passed".postln };
0.exit;
