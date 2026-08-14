/*
Frequency response as a Bode plot: magnitude in dB and phase in degrees against
log frequency. Several responses can be overlaid and compared.

Coefficients are SuperCollider's SOS order, out = a0*x + a1*x1 + a2*x2 + b1*y1 +
b2*y2, so the denominator is 1 - b1 z^-1 - b2 z^-2 with the sign already in b.
*/
PltFilter : PltView {
	// one Event per curve: (mags:, phases:, label:, colorKey:)
	var <curves, <freqs, <sampleRate;
	var <>minHz = 20, <>maxHz = 20000, <>floorDb = -48, <>ceilDb = 24;
	var <>showPhase = true, <>panelGap = 12, <>showLabels = true;
	var colorKeys;

	*new { |coeffs, label, sampleRate = 48000, title = "response",
		points = 320, width = 820, height = 480|
		^super.new(title, width, height)
			.initPltFilter(sampleRate, points)
			.add(coeffs, label)
	}

	// An impulse response measured or rendered elsewhere.
	*fromImpulseResponse { |ir, label, sampleRate = 48000, title = "response",
		points = 320, width = 820, height = 480|
		^super.new(title, width, height)
			.initPltFilter(sampleRate, points)
			.addImpulseResponse(ir, label)
	}

	/*
	A UGen graph, measured by sending an impulse through it on the server. The
	graph takes its input from the first argument, so pass one that reads it:
	PltFilter.fromFunction({ |in| RLPF.ar(in, 800, 0.2) }, action: { |p| p.front })
	*/
	*fromFunction { |func, duration = 0.5, server, label, title = "response",
		points = 320, width = 820, height = 480, action|
		var sr = (server ? Server.default).sampleRate ? 48000;
		{ func.value(Impulse.ar(0)) }.loadToFloatArray(duration, server, { |ir|
			{
				var plot = super.new(title, width, height)
					.initPltFilter(sr, points)
					.addImpulseResponse(ir, label ? "measured");
				action.value(plot);
			}.defer;
		});
	}

	// Log spaced, so a Bode plot has points where the eye needs them.
	*logFreqs { |minHz = 20, maxHz = 20000, points = 320|
		var lo = minHz.max(1).log10, hi = maxHz.log10;
		^Array.fill(points, { |i| 10 ** (lo + (i / (points - 1).max(1) * (hi - lo))) })
	}

	/*
	Response of one SOS section at the given frequencies: [magnitudes, phases],
	phases in radians. Evaluated on the unit circle by hand rather than through
	Complex, which keeps it dependency free and readable.
	*/
	*biquadResponse { |coeffs, freqs, sampleRate = 48000|
		var a0 = coeffs[0] ? 0, a1 = coeffs[1] ? 0, a2 = coeffs[2] ? 0;
		var b1 = coeffs[3] ? 0, b2 = coeffs[4] ? 0;
		var mags = Array.newClear(freqs.size), phases = Array.newClear(freqs.size);
		freqs.do { |f, i|
			var w = 2pi * f / sampleRate;
			var c1 = cos(w), s1 = sin(w), c2 = cos(2 * w), s2 = sin(2 * w);
			var nRe = a0 + (a1 * c1) + (a2 * c2);
			var nIm = ((a1 * s1) + (a2 * s2)).neg;
			var dRe = 1 - (b1 * c1) - (b2 * c2);
			var dIm = (b1 * s1) + (b2 * s2);
			var dMag = hypot(dRe, dIm);
			mags[i] = if(dMag <= 0) { inf } { hypot(nRe, nIm) / dMag };
			phases[i] = atan2(nIm, nRe) - atan2(dIm, dRe);
		};
		^[mags, phases]
	}

	/*
	The same, from an impulse response: one DFT term per requested frequency.

	Direct evaluation rather than an FFT and interpolation, because the points are
	log spaced and an FFT gives linear bins, which is exactly where a Bode plot
	needs resolution least.
	*/
	*impulseResponse { |ir, freqs, sampleRate = 48000|
		var n = ir.size;
		var mags = Array.newClear(freqs.size), phases = Array.newClear(freqs.size);
		freqs.do { |f, i|
			var w = 2pi * f / sampleRate, re = 0, im = 0;
			n.do { |k|
				var x = ir[k];
				re = re + (x * cos(w * k));
				im = im - (x * sin(w * k));
			};
			mags[i] = hypot(re, im);
			phases[i] = atan2(im, re);
		};
		^[mags, phases]
	}

	initPltFilter { |argSampleRate, points|
		sampleRate = argSampleRate;
		colorKeys = [\ink, \mint, \gold, \rose, \trace];
		curves = [];
		freqs = this.class.logFreqs(minHz, maxHz.min(sampleRate / 2), points);
		padTop = 32;      // the legend row sits above the frame
		xLabel = "Hz"; yLabel = "dB";
		^this
	}

	// Overlay another response, from SOS coefficients.
	add { |coeffs, label|
		var r = this.class.biquadResponse(coeffs, freqs, sampleRate);
		^this.prAddCurve(r, label ?? { coeffs.collect(_.round(0.001)).asString })
	}

	addImpulseResponse { |ir, label|
		var r = this.class.impulseResponse(ir, freqs, sampleRate);
		^this.prAddCurve(r, label ?? { ir.size.asString + "sample IR" })
	}

	prAddCurve { |response, label|
		curves = curves.add((
			mags: response[0], phases: response[1], label: label,
			colorKey: colorKeys[curves.size % colorKeys.size]
		));
		this.refresh;
		^this
	}

	// The magnitude at one frequency, in dB, of the first curve.
	magnitudeAt { |hz, index = 0|
		var c = curves[index];
		var nearest = freqs.indexIn(hz);
		^PlotLib.ampDb(c[\mags][freqs.indexOf(nearest) ? 0], floorDb)
	}

	dataBounds { ^[minHz.max(1).log10, maxHz.min(sampleRate / 2).log10, floorDb, ceilDb] }

	xTickValues { |b| ^PlotLib.freqTicks(minHz, maxHz.min(sampleRate / 2)).collect(_.log10) }
	xTickLabel { |value, b| ^PlotLib.freqLabel(10 ** value) }

	caption {
		^curves.size.asString + if(curves.size == 1) { "response," } { "responses," }
			+ (sampleRate / 1000).round(0.1) ++ " kHz"
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var b = this.dataBounds;
		var magRect = r, phaseRect;

		if(showPhase) {
			var h = (r.height - panelGap) * 0.68;
			magRect = Rect(r.left, r.top, r.width, h);
			phaseRect = Rect(r.left, r.top + h + panelGap, r.width,
				r.height - h - panelGap);
		};

		this.drawPanel(magRect, b, { this.prDrawCurves(magRect, b, \mags) },
			showPhase.not, true);
		if(showPhase) {
			var pb = [b[0], b[1], -180, 180];
			this.drawPanel(phaseRect, pb, { this.prDrawCurves(phaseRect, pb, \phases) },
				true, true);
			Pen.stringLeftJustIn(" degrees",
				Rect(phaseRect.left, phaseRect.top + 3, phaseRect.width, 12),
				PlotLib.font(9), PlotLib.color(\muted));
		};

		if(showLabels) { this.prLegend(magRect) };
		this.prCaption(v, r);
	}

	prLegend { |r|
		var x = r.left, font = PlotLib.font(9);
		curves.do { |c|
			var label = c[\label];
			var w = try { label.bounds(font).width } { label.size * 6 };
			Pen.strokeColor = PlotLib.color(c[\colorKey]);
			Pen.width = PlotLib.lineWidth(2);
			Pen.line(Point(x, r.top - 9), Point(x + 12, r.top - 9));
			Pen.stroke;
			Pen.stringLeftJustIn(label, Rect(x + 16, r.top - 16, w + 8, 13),
				font, PlotLib.color(c[\colorKey]));
			x = x + 16 + w + 16;
		};
	}

	prDrawCurves { |r, b, key|
		curves.do { |c|
			var values = c[key];
			Pen.strokeColor = PlotLib.color(c[\colorKey]);
			Pen.width = PlotLib.lineWidth(1.5);
			freqs.do { |f, i|
				var y = if(key == \mags) {
					this.yPix(PlotLib.ampDb(values[i], floorDb), r, b)
				} {
					// unwrapped would climb off the panel; wrapped to +-180 is
					// how a Bode plot is read
					this.yPix(values[i].wrap(-pi, pi) * 180 / pi, r, b)
				};
				var p = Point(this.xPix(f.log10, r, b), y);
				if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		};
	}
}
