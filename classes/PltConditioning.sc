/*
The wire from an analyser to a parameter, one panel per decision: smoothing,
scaling, normalising against the material, thresholding with hysteresis, and the
mapping to a control with the events a trigger would fire instead.

Each decision looks technical and is compositional. The parameters are plain
setters; call refresh after changing them.
*/
PltConditioning : PltView {
	var <raw, <rate, <name, <unit;
	var <>lag = 0.25, <>warp = \log, <>lo = 0.05, <>hi = 0.95, <>on = 0.6, <>off = 0.4;
	var <>spec, <>specName = "cutoff", <>panelGap = 8;
	var panelSpec;

	// values: one descriptor at rate frames per second.
	*new { |values, rate = 50, name = "descriptor", unit = "", title,
		width = 900, height = 680|
		^super.new(title ? name, width, height).initPltConditioning(values, rate, name, unit)
	}

	*fromSoundFile { |path, descriptor = \centroid, fftSize = 1024, hop = 0.5, title,
		width = 900, height = 680|
		var a = PltSpectrogram.framesFromSoundFile(path, fftSize, hop), prev, values;
		if(a.isNil) { ^nil };
		values = a[\frames].collect { |frame|
			var v = PltObservers.describe(descriptor, frame, a[\sampleRate], fftSize, prev);
			prev = frame;
			v
		};
		^this.new(values, a[\frames].size / a[\duration].max(1e-9), descriptor.asString,
			if([\centroid, \rolloff, \peak, \spread].includes(descriptor)) { "Hz" } { "" },
			title ?? { PathName(path).fileName }, width, height)
	}

	// One-pole lag with Lag's definition of lagTime: 60 dB of convergence.
	*lag { |values, rate, seconds|
		var coef = if(seconds <= 0) { 0 } { exp(0.001.log / (seconds * rate)) };
		var y = values.first ? 0;
		^values.collect { |x| y = x + (coef * (y - x)) }
	}

	// On above on, off below off; between them, whatever it was.
	*hysteresis { |values, on = 0.6, off = 0.4|
		var state = 0;
		^values.collect { |v|
			if(v >= on) { state = 1 } { if(v < off) { state = 0 } };
			state
		}
	}

	*rises { |gate|
		var out = Array.new;
		gate.do { |g, i| if((g > 0) and: { (i == 0) or: { gate[i - 1] <= 0 } }) { out = out.add(i) } };
		^out
	}

	initPltConditioning { |values, argRate, argName, argUnit|
		raw = values;
		rate = argRate;
		name = argName;
		unit = argUnit;
		spec = ControlSpec(200, 4000, \exp);
		xLabel = "seconds";
		yTicks = 3;
		^this
	}

	duration { ^raw.size / rate.max(1e-9) }

	// Every stage, recomputed from the parameters on each call.
	stages {
		var smooth = this.class.lag(raw, rate, lag);
		var scaled = if(warp == \log) { smooth.collect { |v| v.max(1e-6).log2 } } { smooth };
		var a = PltObservers.percentile(scaled, lo), b = PltObservers.percentile(scaled, hi);
		var norm = scaled.collect { |v| if(b > a) { (v - a) / (b - a) } { 0.5 } };
		var gate = this.class.hysteresis(norm, on, off);
		var bare = this.class.hysteresis(norm, (on + off) / 2, (on + off) / 2);
		^(smooth: smooth, scaled: scaled, norm: norm.collect(_.clip(0, 1)),
			clipped: norm.count { |v| (v < 0) or: { v > 1 } } / norm.size.max(1),
			gate: gate, events: this.class.rises(gate), bare: this.class.rises(bare),
			control: norm.collect { |v| spec.map(v.clip(0, 1)) })
	}

	yTickLabel { |value, b|
		if(panelSpec.notNil) { ^PlotLib.fmt(panelSpec.map(value), panelSpec.maxval - panelSpec.minval) };
		^super.yTickLabel(value, b)
	}

	caption { ^raw.size.asString + "frames at" + rate.round(0.1) + "per second" }

	prLine { |values, r, b, color, width|
		Pen.strokeColor = color;
		Pen.width = PlotLib.lineWidth(width);
		values.do { |v, i|
			var p = Point(this.xPix(i / rate, r, b), this.yPix(v, r, b));
			if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;
	}

	prEventTicks { |frames, r, b, color, top|
		Pen.strokeColor = color;
		Pen.width = PlotLib.lineWidth(1.5);
		frames.do { |i|
			var px = this.xPix(i / rate, r, b);
			if(top) { Pen.line(Point(px, r.top), Point(px, r.top + (r.height * 0.18))) } {
				Pen.line(Point(px, r.bottom), Point(px, r.bottom - (r.height * 0.18)))
			};
		};
		Pen.stroke;
	}

	prDraw { |v|
		var r = this.plotRect(v), s = this.stages, dur = this.duration;
		var h = (r.height - (panelGap * 4)) / 5;
		var font = PlotLib.font(9), muted = PlotLib.color(\muted);
		var panels = Array.fill(5, { |i| Rect(r.left, r.top + (i * (h + panelGap)), r.width, h) });
		var words = #["smoothing", "scaling", "normalising", "hysteresis", "mapping"];
		var label = { |i, text, key = \ink|
			Pen.stringLeftJustIn(" " ++ if(showText) { text } { words[i] }, Rect(panels[i].left + 2, panels[i].top + 3,
				panels[i].width, 12), font, PlotLib.color(key))
		};
		var e, b;

		e = PlotLib.extent(raw) ? #[0, 1];
		b = [0, dur, e[0], e[1]];
		this.drawPanel(panels[0], b, {
			this.prLine(raw, panels[0], b, muted.copy.alpha_(PlotLib.alphaFor(0.5)), 0.8);
			this.prLine(s[\smooth], panels[0], b, PlotLib.color(\ink), 1.3);
		}, false, true);
		label.(0, "1  smoothing: lag " ++ lag ++ " s on " ++ name
			++ if(unit.size > 0) { " (" ++ unit ++ ")" } { "" });

		e = PlotLib.extent(s[\scaled]) ? #[0, 1];
		b = [0, dur, e[0], e[1]];
		this.drawPanel(panels[1], b, {
			this.prLine(s[\scaled], panels[1], b, PlotLib.color(\mint), 1.3);
		}, false, true);
		label.(1, "2  scaling: " ++ if(warp == \log) { "log2, in octaves" } { "linear" }, \mint);

		b = [0, dur, 0, 1];
		this.drawPanel(panels[2], b, {
			this.prLine(s[\norm], panels[2], b, PlotLib.color(\trace), 1.2);
		}, false, true);
		label.(2, "3  normalised against this material, " ++ (lo * 100).round.asInteger
			++ "th to " ++ (hi * 100).round.asInteger ++ "th percentile: "
			++ (s[\clipped] * 100).round.asInteger ++ "% clipped", \trace);

		this.drawPanel(panels[3], b, {
			var p = panels[3];
			Pen.fillColor = PlotLib.color(\gold).copy.alpha_(PlotLib.alphaFor(0.14));
			PltObservers.runs(s[\gate].collect(_ > 0)).do { |run|
				var x0 = this.xPix(run[0] / rate, p, b), x1 = this.xPix(run[1] / rate, p, b);
				Pen.addRect(Rect(x0, p.top, (x1 - x0).max(1), p.height));
			};
			Pen.fill;
			Pen.strokeColor = muted;
			Pen.width = PlotLib.lineWidth(1);
			[on, off].do { |t|
				Pen.line(Point(p.left, this.yPix(t, p, b)), Point(p.right, this.yPix(t, p, b)))
			};
			Pen.stroke;
			this.prLine(s[\norm], p, b, PlotLib.color(\trace).copy.alpha_(PlotLib.alphaFor(0.7)), 1);
			this.prEventTicks(s[\bare], p, b, PlotLib.color(\rose).copy.alpha_(PlotLib.alphaFor(0.8)), false);
			this.prEventTicks(s[\events], p, b, PlotLib.color(\gold), true);
		}, false, true);
		label.(3, "4  hysteresis: on " ++ on ++ ", off " ++ off ++ ", " ++ s[\events].size
			++ " events; a bare threshold at " ++ ((on + off) / 2) ++ " fires "
			++ s[\bare].size ++ " (below)", \gold);

		panelSpec = spec;
		this.drawPanel(panels[4], b, {
			var p = panels[4];
			this.prLine(s[\norm], p, b, PlotLib.color(\ink), 1.3);
			Pen.fillColor = PlotLib.color(\gold);
			s[\events].do { |i|
				Pen.addOval(Rect.aboutPoint(Point(this.xPix(i / rate, p, b),
					this.yPix(s[\norm][i], p, b)), 3, 3));
			};
			Pen.fill;
		}, true, true);
		panelSpec = nil;
		label.(4, "5  mapped to " ++ specName ++ " (" ++ spec.warp.asSpecifier ++ ", "
			++ spec.minval ++ " to " ++ spec.maxval ++ "), or the events a trigger fires instead");

		this.prCaption(v, r);
	}
}
