/*
One spectrum as several listeners receive it, a panel each on one log frequency
axis. The long-term average at a fine resolution is drawn faintly behind every
panel; each listener's own version is drawn over it, and what lies outside its
world is shaded.

Listeners are Events:
	(type: \fft, size: 512)                 the bins a coarser FFT has
	(type: \mel, bands: 40, coeffs: 13)     mel bands, and what 13 MFCCs keep of them
	(type: \aweight)                        the A-weighted spectrum, the meter default
	(type: \centroid)                       the whole spectrum as one number
	(type: \band, lo: 300, hi: 3400, name: "telephone")
*/
PltUmwelt : PltView {
	var <reference, <sampleRate, <listeners, <worlds;
	var <>minHz = 20, <>rangeDb = 80, <>panelGap = 8;

	// spectrum: magnitudes from 0 Hz to Nyquist.
	*new { |spectrum, sampleRate = 48000, listeners, title = "umwelt",
		width = 900, height = 640|
		^super.new(title, width, height)
			.initPltUmwelt(spectrum, sampleRate, listeners ?? { this.defaultListeners })
	}

	*fromSoundFile { |path, listeners, fftSize = 8192, title, width = 900, height = 640|
		var a = PltSpectrogram.framesFromSoundFile(path, fftSize, 0.5);
		if(a.isNil) { ^nil };
		^this.new(this.average(a[\frames]), a[\sampleRate], listeners,
			title ?? { PathName(path).fileName }, width, height)
	}

	*defaultListeners {
		^[(type: \fft, size: 512), (type: \mel, bands: 40, coeffs: 13),
			(type: \aweight), (type: \centroid)]
	}

	// Long-term average: the mean power per bin, as a magnitude.
	*average { |frames|
		var n = frames.size.max(1);
		^Array.fill(frames[0].size, { |k|
			var sum = 0;
			frames.do { |f| sum = sum + f[k].squared };
			(sum / n).sqrt
		})
	}

	*mel { |hz| ^2595 * (1 + (hz / 700)).log10 }
	*melHz { |mel| ^700 * ((10 ** (mel / 2595)) - 1) }

	// Triangular bands evenly spaced in mel: per band its edges, centre, and the
	// bins it covers with their weights.
	*melBank { |sampleRate, bins, bands = 40, loHz = 20, hiHz|
		var binHz = sampleRate / 2 / (bins - 1);
		var m0 = this.mel(loHz), m1 = this.mel(hiHz ?? { sampleRate / 2 });
		var edges = Array.fill(bands + 2, { |i| this.melHz(m0 + ((m1 - m0) * i / (bands + 1))) });
		^Array.fill(bands, { |b|
			var lo = edges[b], mid = edges[b + 1], hi = edges[b + 2];
			var weights = Array.new;
			(lo / binHz).ceil.asInteger.max(0).forBy((hi / binHz).floor.asInteger.min(bins - 1), 1) { |k|
				var f = k * binHz;
				var w = if(f <= mid) { (f - lo) / (mid - lo).max(1e-9) } { (hi - f) / (hi - mid).max(1e-9) };
				if(w > 0) { weights = weights.add([k, w]) };
			};
			(lo: lo, centre: mid, hi: hi, weights: weights,
				nearest: (mid / binHz).round.asInteger.clip(0, bins - 1))
		})
	}

	// Mean power in each band, in dB, so a band reads on the same scale as a bin.
	*melEnergies { |frame, bank|
		^bank.collect { |band|
			var sum = 0, wsum = 0;
			band[\weights].do { |pair| sum = sum + (frame[pair[0]].squared * pair[1]); wsum = wsum + pair[1] };
			// a band narrower than a bin catches none: read the nearest bin instead
			if(wsum <= 0) {
				sum = frame[band[\nearest]].squared; wsum = 1
			};
			10 * ((sum / wsum) + 1e-20).log10
		}
	}

	// DCT-II, the step from mel bands to cepstral coefficients.
	*dct { |values, count|
		var n = values.size;
		^Array.fill(count, { |k|
			var sum = 0;
			values.do { |v, b| sum = sum + (v * cos(pi * k * (b + 0.5) / n)) };
			sum
		})
	}

	// Its inverse, from however many coefficients were kept.
	*idct { |coeffs, size|
		^Array.fill(size, { |b|
			var sum = coeffs[0] / size;
			// counted, not (1 .. size - 1), which runs backwards for one coefficient
			(coeffs.size - 1).do { |i|
				var k = i + 1;
				sum = sum + (2 / size * coeffs[k] * cos(pi * k * (b + 0.5) / size))
			};
			sum
		})
	}

	// IEC 61672 A-weighting in dB, 0 at 1 kHz.
	*aWeight { |hz|
		// float throughout: an Integer hz overflows 32 bits at f to the fourth
		var f2 = hz.asFloat.squared;
		var ra = (12194.0.squared * f2.squared) / ((f2 + 20.6.squared)
			* ((f2 + 107.7.squared) * (f2 + 737.9.squared)).sqrt * (f2 + 12194.0.squared));
		^(20 * (ra.max(1e-20)).log10) + 2.0
	}

	initPltUmwelt { |spectrum, argRate, argListeners|
		reference = spectrum;
		sampleRate = argRate;
		listeners = argListeners;
		worlds = listeners.collect { |l| this.prWorld(l) };
		xLabel = "Hz"; yLabel = "dB";
		yTicks = 5;
		^this
	}

	binHz { ^sampleRate / 2 / (reference.size - 1).max(1) }

	nyquist { ^sampleRate / 2 }

	/*
	What one listener receives: steps or a curve as [lo, hi, dB] or [hz, dB], the
	ranges outside its world, a vertical mark, and a label.
	*/
	prWorld { |l|
		var binHz = this.binHz, refDb = reference.collect { |m| PlotLib.ampDb(m, -160) };
		^switch(l[\type])
		{ \fft } {
			var size = l[\size] ? 512, df = sampleRate / size;
			var count = (size div: 2) + 1;
			// the loudest fine bin wins: two partials in one coarse bin become one
			var steps = Array.fill(count, { |k|
				var lo = ((k - 0.5) * df).max(0), hi = ((k + 0.5) * df).min(this.nyquist), m = 0;
				(lo / binHz).ceil.asInteger.forBy(((hi / binHz).floor.asInteger).min(reference.size - 1), 1) { |i|
					m = max(m, reference[i])
				};
				[lo, hi, PlotLib.ampDb(m, -160)]
			});
			(steps: steps, outside: [[0, df]], short: size.asString + "point FFT",
				label: size.asString ++ "-point FFT: " ++ PlotLib.fmt(df, 100)
					++ " Hz per bin, nothing resolved below " ++ PlotLib.freqLabel(df))
		}
		{ \mel } {
			var bands = l[\bands] ? 40, coeffs = l[\coeffs] ? 13;
			var lo = l[\lo] ? 20, hi = l[\hi] ?? { this.nyquist.min(16000) };
			var bank = this.class.melBank(sampleRate, reference.size, bands, lo, hi);
			var energies = this.class.melEnergies(reference, bank);
			var kept = this.class.idct(this.class.dct(energies, coeffs), bands);
			// a triangle's edges are its neighbours' centres, so halfway to each tiles
			(steps: bank.collect { |band, i|
				[(band[\lo] + band[\centre]) / 2, (band[\centre] + band[\hi]) / 2, energies[i]]
			},
				curve: bank.collect { |band, i| [band[\centre], kept[i]] },
				outside: [[0, lo], [hi, this.nyquist]], short: bands.asString + "mel bands",
				label: bands.asString + "mel bands, the line is what" + coeffs + "MFCCs keep of them")
		}
		{ \aweight } {
			var curve = refDb.collect { |db, k|
				var f = (k * binHz).max(1);
				[f, db + this.class.aWeight(f)]
			};
			(curve: curve, removed: true, short: "A-weighted",
				label: "A-weighted: " ++ this.class.aWeight(100).round(0.1)
					++ " dB at 100 Hz, " ++ this.class.aWeight(50).round(0.1) ++ " dB at 50 Hz")
		}
		{ \centroid } {
			var c = PltObservers.describe(\centroid, reference, sampleRate, (reference.size - 1) * 2);
			(mark: c, outside: [[0, this.nyquist]], short: "centroid",
				label: "spectral centroid: the whole spectrum as one number, "
					++ c.round.asInteger ++ " Hz")
		}
		{ \band } {
			var lo = l[\lo] ? 20, hi = l[\hi] ? 20000;
			(curve: refDb.collect { |db, k| [(k * binHz).max(1), db] }
				.select { |p| (p[0] >= lo) and: { p[0] <= hi } },
				outside: [[0, lo], [hi, this.nyquist]], short: l[\name] ? "band",
				label: (l[\name] ? "band") ++ ": " ++ PlotLib.freqLabel(lo) ++ " to "
					++ PlotLib.freqLabel(hi) ++ " Hz")
		}
		{ ("PltUmwelt: no listener type" + l[\type]).warn; (label: "?") }
	}

	dataBounds {
		var top = reference.collect { |m| PlotLib.ampDb(m, -160) }.maxItem + 6;
		^[minHz.log10, this.nyquist.log10, top - rangeDb, top]
	}

	xTickValues { |b| ^PlotLib.freqTicks(minHz, this.nyquist).collect(_.log10) }
	xTickLabel { |value, b| ^PlotLib.freqLabel(10 ** value) }

	caption { ^"faint: the average spectrum at" + PlotLib.fmt(this.binHz, 10) + "Hz per bin, itself a listener" }

	prX { |hz, r, b| ^this.xPix(hz.max(minHz).log10, r, b) }

	prDraw { |v|
		var r = this.plotRect(v);
		var n = worlds.size;
		var h = (r.height - (panelGap * (n - 1))) / n;
		var b = this.dataBounds;
		var font = PlotLib.font(9), muted = PlotLib.color(\muted);
		var keys = #[\ink, \mint, \gold, \rose, \trace];
		var refDb = reference.collect { |m| PlotLib.ampDb(m, -160) };

		worlds.do { |w, i|
			var panel = Rect(r.left, r.top + (i * (h + panelGap)), r.width, h);
			var col = PlotLib.color(keys.wrapAt(i));
			this.drawPanel(panel, b, { this.prDrawWorld(w, panel, b, col, refDb) },
				i == (n - 1), true);
			// on a ground patch: a loud spectrum reaches the top of its panel
			Pen.fillColor = PlotLib.color(\bg).copy.alpha_(0.85);
			Pen.fillRect(Rect(panel.left + 2, panel.top + 2, this.prLabel(w).bounds(font).width + 12, 14));
			Pen.stringLeftJustIn(" " ++ this.prLabel(w), Rect(panel.left + 2, panel.top + 3,
				panel.width, 12), font, col);
		};
		this.prCaption(v, r);
	}

	prLabel { |w| ^if(showText) { w[\label] } { w[\short] ? w[\label] } }

	prDrawWorld { |w, r, b, col, refDb|
		var muted = PlotLib.color(\muted);
		var clipY = { |db| this.yPix(db.clip(b[2], b[3]), r, b) };

		// outside this listener's world
		Pen.fillColor = muted.copy.alpha_(PlotLib.alphaFor(0.14));
		(w[\outside] ? []).do { |range|
			var x0 = this.prX(range[0], r, b).max(r.left), x1 = this.prX(range[1], r, b).min(r.right);
			if(x1 > x0) { Pen.addRect(Rect(x0, r.top, x1 - x0, r.height)) };
		};
		Pen.fill;

		// the reference, faint behind every panel
		Pen.strokeColor = muted.copy.alpha_(PlotLib.alphaFor(0.45));
		Pen.width = PlotLib.lineWidth(0.8);
		refDb.do { |db, k|
			var p = Point(this.prX((k * this.binHz).max(minHz), r, b), clipY.(db));
			if(k == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;

		if(w[\steps].notNil) {
			Pen.strokeColor = if(w[\curve].notNil) { col.copy.alpha_(PlotLib.alphaFor(0.5)) } { col };
			Pen.width = PlotLib.lineWidth(1.3);
			w[\steps].do { |s, i|
				var x0 = this.prX(s[0], r, b), x1 = this.prX(s[1], r, b), y = clipY.(s[2]);
				if(i == 0) { Pen.moveTo(Point(x0, y)) } { Pen.lineTo(Point(x0, y)) };
				Pen.lineTo(Point(x1, y));
			};
			Pen.stroke;
		};

		if(w[\curve].notNil) {
			// what the weighting takes away, between the reference and the curve
			if(w[\removed] == true) {
				Pen.fillColor = PlotLib.color(\rose).copy.alpha_(PlotLib.alphaFor(0.18));
				w[\curve].do { |p, k| var pt = Point(this.prX(p[0], r, b), clipY.(p[1]));
					if(k == 0) { Pen.moveTo(pt) } { Pen.lineTo(pt) } };
				(w[\curve].size - 1).forBy(0, -1) { |k|
					Pen.lineTo(Point(this.prX(w[\curve][k][0], r, b), clipY.(refDb[k])))
				};
				Pen.fill;
			};
			Pen.strokeColor = col;
			Pen.width = PlotLib.lineWidth(1.4);
			w[\curve].do { |p, k|
				var pt = Point(this.prX(p[0], r, b), clipY.(p[1]));
				if(k == 0) { Pen.moveTo(pt) } { Pen.lineTo(pt) };
			};
			Pen.stroke;
		};

		if(w[\mark].notNil) {
			var x = this.prX(w[\mark], r, b);
			Pen.strokeColor = col;
			Pen.width = PlotLib.lineWidth(2);
			Pen.line(Point(x, r.top), Point(x, r.bottom));
			Pen.stroke;
		};
	}
}
