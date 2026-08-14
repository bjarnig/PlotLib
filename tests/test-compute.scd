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
