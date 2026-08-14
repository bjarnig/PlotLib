/*
Render every view to images/ so the drawing can be looked at, and so the README
screenshots regenerate instead of going stale.

	sclang tests/render-shots.scd

Image.fromWindow makes Qt render the widget itself, so nothing on screen can
intrude and the result is the same on every run. The waits matter: layout and
drawFunc have not run at the moment front returns.
*/

// absolutePath first: nowExecutingPath is relative when sclang was given a
// relative path, and Image:write cannot create a relative directory
var dir = thisProcess.nowExecutingPath.absolutePath.dirname.dirname +/+ "images/";
var shot;

shot = { |name, view, wait = 0.4|
	var path = dir ++ name ++ ".png";
	view.front;
	wait.wait;
	view.writeImage(path);
	view.close;
	0.2.wait;
	("  wrote " ++ name ++ ".png").postln;
};

Routine({
	var xs, ys, values, ev, meter, spec, now;

	"rendering to " .post; dir.postln;

	// the same plot in each theme, for the theme section of the README
	[\darkBlue, \dark, \light].do { |name|
		PlotLib.theme_(name);
		0.1.wait;
		shot.value("theme-" ++ name.asString.toLower,
			PltBifurcation({ |x, r| r * x * (1 - x) }, 2.4, 4.0, 0.5, 460, 200, 90,
				name.asString, 640, 360).marker_(3.82),
			0.6);
	};
	PlotLib.theme_(\darkBlue);
	0.1.wait;

	// one light rendering of a filled plot too: alpha and hairlines behave
	// differently on a white ground, so this is the one to check
	PlotLib.theme_(\light);
	0.1.wait;
	shot.value("histogram-light",
		PltHistogram({ exprand(0.01, 1.0) } ! 8000, 0, 1, 40, "light theme", 640, 340));
	PlotLib.theme_(\darkBlue);
	0.1.wait;

	// scatter: a Lissajous figure, so a wrong axis mapping is obvious
	xs = (0 .. 1200).collect { |i| sin(i * 0.031) };
	ys = (0 .. 1200).collect { |i| sin(i * 0.043 + 0.4) };
	shot.value("scatter", PltScatter(xs, ys, "scatter: Lissajous").dotSize_(2).alpha_(0.6));

	// histogram: exponential, which must show a clear slope rather than a block
	values = { exprand(0.01, 1.0) } ! 8000;
	shot.value("histogram", PltHistogram(values, 0, 1, 40, "histogram: exprand"));

	shot.value("bifurcation",
		PltBifurcation({ |x, r| r * x * (1 - x) }, 2.4, 4.0, 0.5, 700, 300, 120,
			"logistic map").marker_(3.82),
		0.8);

	shot.value("phase",
		PltPhase({ |x, y| [1 - (1.4 * x * x) + y, 0.3 * x] }, 0.1, 0.1,
			"Henon attractor", 400, 14000),
		0.8);

	// events: placed with explicit times, so the shot is reproducible
	ev = PltEvents("events: a placed sequence", 8, 150, 1600);
	now = Main.elapsedTime;
	400.do { |i|
		var t = now - 8 + (i * 8 / 400);
		ev.push(exprand(180.0, 1500.0), rrand(0.05, 0.5), t);
	};
	shot.value("events", ev);

	// meter: fed from the language, no server needed
	meter = PltMeter(4, 0, nil, "meter: four channels");
	meter.pushFrame([1.0, 0.5, 0.12, 0.01], [0.7, 0.35, 0.08, 0.004]);
	shot.value("meter", meter);

	// spectrum: a synthetic frame of a harmonic series with a formant peak
	spec = PltSpectrum(0, 1024, nil, "spectrum: synthetic frame");
	spec.maxHz_(12000);
	4.do {
		var frame = 0.0 ! 513;
		513.do { |i|
			var f = i * 48000 / 1024;
			var harmonic = if((i % 8 == 0) and: { i > 0 }) { 1 } { 0.02 };
			var formant = exp((f - 1400).squared.neg / 2e6);
			// pushFrame divides by fftSize/4, so scale up to land near 0 dBFS
			frame[i] = harmonic * (0.2 + formant) * 256;
		};
		spec.pushFrame(frame);
	};
	shot.value("spectrum", spec);

	spec = PltSpectrum(0, 1024, nil, "spectrum: log frequency");
	spec.logFreq_(true).minHz_(20).maxHz_(20000);
	4.do {
		var frame = 0.0 ! 513;
		513.do { |i|
			var f = (i * 48000 / 1024).max(1);
			frame[i] = (1 / (1 + (f / 500))) * 300 + (10.0.rand);
		};
		spec.pushFrame(frame);
	};
	shot.value("spectrum-log", spec);

	// recorded sound: the file that ships with SuperCollider, so this runs anywhere
	{
		var path = Platform.resourceDir +/+ "sounds/a11wlk01.wav";
		var stereo;
		shot.value("wave-file", PltWave.fromSoundFile(path, 900, "a11wlk01.wav"));
		shot.value("wave-db",
			PltWave.fromSoundFile(path, 900, "a11wlk01.wav, dB").dbScale_(true));
		stereo = [
			Signal.fill(88200, { |i| sin(i * 0.02) * (1 - (i / 88200)) }),
			Signal.fill(88200, { |i| sin(i * 0.031) * ((i / 88200) ** 2) * 0.8 })
		];
		shot.value("wave-stereo",
			PltWave(stereo, 44100, "stereo, decay and swell", 900, 900, 340));
		shot.value("spectrogram-file",
			PltSpectrogram.fromSoundFile(path, 1024, 0.25, "a11wlk01.wav"), 1.0);
		shot.value("spectrogram-log",
			PltSpectrogram.fromSoundFile(path, 2048, 0.25, "a11wlk01.wav, log")
				.logFreq_(true), 1.0);
		shot.value("envelope-adsr",
			PltEnvelope(Env.adsr(0.02, 0.2, 0.4, 0.5), "Env.adsr"));
		shot.value("envelope-curves",
			PltEnvelope(Env([0, 1, 0.3, 0.8, 0], [0.1, 0.3, 0.2, 0.6],
				[\lin, \exp, \sine, -4]), "mixed curve types"));
	}.value;

	// vectorscope, from data so the picture is reproducible
	{
		var n = 3000;
		var sine = Array.fill(n, { |i| sin(i * 0.04) * 0.8 });
		var wide = Array.fill(n, { |i|
			var m = sin(i * 0.017) * 0.5;
			[m + 0.3.rand2, m + 0.3.rand2]
		}).flop;
		shot.value("vector-mono", PltVector(sine, sine, "identical channels"));
		shot.value("vector-wide",
			PltVector(wide[0], wide[1], "correlated mid, random sides"));
		shot.value("vector-uncorrelated", PltVector(
			Array.fill(n, { 0.7.rand2 }), Array.fill(n, { 0.7.rand2 }),
			"independent noise"));
	}.value;

	"done".postln;
	0.exit;
}).play(AppClock);
