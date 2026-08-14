/*
Palette, themes, fonts and the pure helpers every view shares. No GUI in here, so
all of it can be tested headless.
*/
PlotLib {
	classvar themes, palette, currentTheme;
	classvar fontName;
	classvar <views;      // open views, so a theme change reaches them

	*version { ^"0.1.0" }

	*initClass { views = IdentitySet.new }

	// A palette is not invertible: alpha and hairlines read differently on each
	// ground, which the light palette and alphaFor/lineWidth below compensate for.
	*themes {
		themes ?? {
			themes = (
				// the house palette: Accrete/Ora ground with a blue accent
				darkBlue: (
					ground: \dark,
					bg:    Color.new255(23, 27, 33),
					ink:   Color.new255(120, 165, 210),
					mint:  Color.new255(140, 225, 180),
					gold:  Color.new255(210, 185, 95),
					rose:  Color.new255(210, 120, 120),
					trace: Color.new255(223, 228, 236),
					muted: Color.new255(154, 164, 181),
					edge:  Color.new255(80, 88, 100),
					grid:  Color(1, 1, 1, 0.06)
				),
				// neutral graphite: the data carries the colour, the frame does not
				dark: (
					ground: \dark,
					bg:    Color.new255(18, 18, 18),
					ink:   Color.new255(206, 206, 206),
					mint:  Color.new255(157, 195, 168),
					gold:  Color.new255(203, 183, 121),
					rose:  Color.new255(201, 145, 145),
					trace: Color.new255(240, 240, 240),
					muted: Color.new255(138, 138, 138),
					edge:  Color.new255(74, 74, 74),
					grid:  Color(1, 1, 1, 0.07)
				),
				// for print and for slides on a white ground
				light: (
					ground: \light,
					bg:    Color.new255(255, 255, 255),
					ink:   Color.new255(38, 96, 152),
					mint:  Color.new255(35, 120, 82),
					gold:  Color.new255(146, 110, 20),
					rose:  Color.new255(160, 52, 52),
					trace: Color.new255(26, 26, 26),
					muted: Color.new255(90, 98, 112),
					edge:  Color.new255(168, 176, 188),
					grid:  Color(0, 0, 0, 0.07)
				)
			);
		};
		^themes
	}

	*theme { this.themes; ^currentTheme ?? \darkBlue }

	// Switch theme. Open views are rethemed in place; new ones pick it up anyway.
	*theme_ { |name|
		var found = this.themes[name];
		if(found.isNil) {
			("PlotLib: no theme " ++ name ++ ", have " ++ this.themes.keys).warn;
			^this
		};
		currentTheme = name;
		palette = found;
		views.copy.do { |v| v.applyTheme };
		^this
	}

	*addTheme { |name, palette| this.themes[name] = palette; ^this }

	*palette { this.themes; ^palette ?? { themes[\darkBlue] } }

	*color { |key| ^this.palette[key] ?? { this.palette[\ink] } }

	// Replace one entry of the current theme: PlotLib.setColor(\ink, Color.red)
	*setColor { |key, color| this.palette[key] = color; ^this }

	*isLight { ^this.palette[\ground] == \light }

	// Transparency encodes density in the scatter plots, and the same alpha does
	// not mean the same weight on both grounds.
	*alphaFor { |alpha| ^if(this.isLight) { (alpha * 1.7).min(1) } { alpha } }

	// A hairline needs about double the weight to read on white.
	*lineWidth { |width| ^if(this.isLight) { width * 1.6 } { width } }

	// Resolved lazily: Font.availableFonts needs the GUI, which does not exist
	// at class-init time, and unavailable names fall back silently.
	*fontName {
		fontName ?? {
			var wanted = ["Helvetica Neue", "Helvetica", "Arial"];
			var available = try { Font.availableFonts.collect(_.asString) } { [] };
			fontName = wanted.detect { |n| available.includesEqual(n) } ?? { wanted.last };
		};
		^fontName
	}

	*font { |size = 10, bold = false| ^Font(this.fontName, size, bold) }

	// Linear rescale, clipped to the target range.
	*map { |value, inLo, inHi, outLo, outHi|
		var t = if(inHi == inLo) { 0 } { (value - inLo) / (inHi - inLo) };
		^outLo + (t.clip(0, 1) * (outHi - outLo))
	}

	// Count values into equal bins across [lo, hi]. Non-numbers and NaN are skipped.
	*histogram { |values, lo, hi, bins = 40|
		var counts = Array.fill(bins, 0);
		values.do { |v|
			var t, i;
			if(v.isNumber and: { v.isNaN.not }) {
				t = if(hi == lo) { 0 } { (v - lo) / (hi - lo) };
				i = (t.clip(0, 0.9999) * bins).floor.asInteger;
				counts[i] = counts[i] + 1;
			}
		};
		^counts
	}

	// min and max in one pass, ignoring NaN; nil for an empty or all-NaN input.
	*extent { |values|
		var lo, hi;
		values.do { |v|
			if(v.isNumber and: { v.isNaN.not }) {
				if(lo.isNil or: { v < lo }) { lo = v };
				if(hi.isNil or: { v > hi }) { hi = v };
			}
		};
		if(lo.isNil) { ^nil };
		// a degenerate range would divide by zero in every mapping
		if(lo == hi) { ^[lo - 0.5, hi + 0.5] };
		^[lo, hi]
	}

	// "Nice" tick values inside [lo, hi]: steps of 1, 2 or 5 times a power of ten.
	*ticks { |lo, hi, maxCount = 6|
		var span, raw, mag, norm, step, first, out;
		if(hi <= lo or: { maxCount < 2 }) { ^[lo] };
		span = hi - lo;
		raw = span / (maxCount - 1);
		mag = 10 ** raw.log10.floor;
		norm = raw / mag;
		// 2.5 is in the set because without it a span like 0.93 or 84 jumps
		// straight to a step of 5, leaving a plot with two labelled lines
		step = mag * case
			{ norm <= 1 } { 1 }
			{ norm <= 2 } { 2 }
			{ norm <= 2.5 } { 2.5 }
			{ norm <= 5 } { 5 }
			{ true } { 10 };
		first = (lo / step).ceil * step;
		out = Array.new;
		// recompute each tick from the index: accumulating 0.1 steps drifts
		(((hi - first) / step).floor.asInteger.max(0) + 1).do { |i|
			var v = first + (i * step);
			if(v <= (hi + (step * 1e-9))) { out = out.add(v) };
		};
		^out
	}

	// Compact axis label, precision from the span it belongs to. A whole number
	// loses its decimal: an axis reading "30.0" or "5000.0" is noise.
	*fmt { |value, span = 1|
		var q = case
			{ span >= 100 } { 1 }
			{ span >= 10 } { 0.1 }
			{ span >= 1 } { 0.01 }
			{ true } { 0.001 };
		var rounded = value.round(q);
		if((rounded - rounded.round(1)).abs < (q * 0.01)) {
			^rounded.round(1).asInteger.asString
		};
		^rounded.asString
	}

	// Amplitude to dB with a floor, so silence maps to the bottom of a meter
	// instead of -inf.
	*ampDb { |amp, floor = -72| ^amp.abs.max(0).ampdb.max(floor) }

	// Every class in the quark, for the help overview.
	*classes {
		^[PltView, PltScatter, PltHistogram, PltBifurcation, PltPhase,
			PltEvents, PltMeter, PltSpectrum,
			PltWave, PltEnvelope, PltSpectrogram, PltVector,
			PltTrack, PltMap]
	}
}
