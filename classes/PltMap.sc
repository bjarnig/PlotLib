/*
A mapping curve: what a control does to a value. Input across, output up.

	PltMap(\freq.asSpec).front;
	PltMap(\freq.asSpec).add(ControlSpec(20, 20000, \lin), "linear").front;
	PltMap(\freq.asSpec).marker_(0.5).front;

This is the plot for the question a synthesis class asks all the time: the slider
moved a quarter of the way, so what happened to the sound? An exponential
frequency spec and a linear one look identical as numbers in a SynthDef and
completely different here.

Takes a ControlSpec, a Symbol that names one (\freq, \amp, \pan), a Warp, or a
Function of a value from 0 to 1. Several can be overlaid to be compared.
*/
PltMap : PltView {
	// one Event per curve: (spec:, label:, xs:, ys:, colorKey:)
	var <curves;
	var <>columns = 240, <marker;
	var <>logOutput = false, <>showLabels = true;
	var colorKeys;

	*new { |spec, label, title = "mapping", width = 700, height = 420|
		^super.new(title, width, height).initPltMap(spec, label)
	}

	// A function over a domain of its own, rather than over 0 to 1.
	*function { |func, lo = 0, hi = 1, label, title = "mapping",
		width = 700, height = 420|
		^this.new({ |t| func.value(lo + (t * (hi - lo))) }, label, title, width, height)
	}

	/*
	The curve as plain data: [inputs, outputs], with inputs from 0 to 1.

	A ControlSpec, a Warp and a Function all answer to map or value, so the
	caller does not have to care which it is.
	*/
	*points { |spec, columns = 240|
		var xs = Array.fill(columns, { |i| i / (columns - 1).max(1) });
		^[xs, xs.collect { |t| this.mapWith(spec, t) }]
	}

	// One value through whichever kind of mapping this is.
	*mapWith { |spec, value|
		if(spec.isKindOf(Function)) { ^spec.value(value) };
		if(spec.respondsTo(\map)) { ^spec.map(value) };
		^spec.value(value)
	}

	*asSpec { |spec|
		if(spec.isKindOf(Symbol)) { ^spec.asSpec };
		^spec
	}

	initPltMap { |spec, label|
		colorKeys = [\ink, \mint, \gold, \rose, \trace];
		curves = [];
		padTop = 32;      // room for the legend row above the frame
		xLabel = "control"; yLabel = "value";
		this.add(spec, label);
		^this
	}

	// Overlay another mapping, to be compared with the first.
	add { |spec, label|
		var resolved = this.class.asSpec(spec);
		var pts = this.class.points(resolved, columns);
		curves = curves.add((
			spec: resolved,
			label: label ?? { this.prLabelFor(resolved) },
			xs: pts[0], ys: pts[1],
			colorKey: colorKeys[curves.size % colorKeys.size]
		));
		if(curves.size == 1) { yLabel = this.prUnitsFor(resolved) };
		this.refresh;
		^this
	}

	prLabelFor { |spec|
		if(spec.isKindOf(ControlSpec)) {
			^(spec.warp.class.name.asString.replace("Warp", "").toLower
				+ spec.minval.round(0.01).asString ++ ".." ++ spec.maxval.round(0.01))
		};
		if(spec.isKindOf(Warp)) { ^spec.class.name.asString };
		^"function"
	}

	prUnitsFor { |spec|
		if(spec.isKindOf(ControlSpec) and: { spec.units.size > 0 }) { ^spec.units };
		^"value"
	}

	// Where the control currently sits, from 0 to 1. Drawn as a line with a dot on
	// each curve, and the mapped values printed.
	marker_ { |value| marker = value; this.refresh; ^this }

	// The mapped value of every curve at the marker.
	valuesAtMarker {
		if(marker.isNil) { ^nil };
		^curves.collect { |c| this.class.mapWith(c[\spec], marker) }
	}

	dataBounds {
		var all = curves.collect { |c| c[\ys] }.flatten;
		var e = PlotLib.extent(all) ? #[0, 1];
		var lo = e[0], hi = e[1];
		if(logOutput) {
			^[0, 1, lo.max(1e-6).log10, hi.max(1e-5).log10]
		};
		^[0, 1, min(0, lo), hi]
	}

	// The output axis in decades, when the mapping spans them.
	yTickValues { |b|
		if(logOutput.not) { ^super.yTickValues(b) };
		^(b[2].floor.asInteger .. b[3].ceil.asInteger)
			.collect { |d| d.asFloat }
			.select { |d| (d >= b[2]) and: { d <= b[3] } }
	}

	yTickLabel { |value, b|
		if(logOutput.not) { ^super.yTickLabel(value, b) };
		^PlotLib.fmt(10 ** value, 10 ** value)
	}

	caption {
		var vals = this.valuesAtMarker;
		if(vals.isNil) { ^curves.size.asString + "curves" };
		^"at" + marker.round(0.001) ++ ":"
			+ vals.collect { |v| v.round(0.01) }.join(", ")
	}

	prYOf { |value, r, b|
		^this.yPix(if(logOutput) { value.max(1e-6).log10 } { value }, r, b)
	}

	drawData { |v, r, b|
		curves.do { |c|
			var col = PlotLib.color(c[\colorKey]);
			var xs = c[\xs], ys = c[\ys];

			Pen.strokeColor = col;
			Pen.width = PlotLib.lineWidth(1.5);
			xs.size.do { |i|
				var p = Point(this.xPix(xs[i], r, b), this.prYOf(ys[i], r, b));
				if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;

		};

		/*
		The legend goes in the top margin, outside the plot, as a row of swatches.
		A label at the end of each curve collides when mappings share a range,
		because they all end at the same value; a stacked legend inside the corner
		then sits on top of the curves where they converge.
		*/
		if(showLabels) {
			var x = r.left;
			var font = PlotLib.font(9);
			curves.do { |c|
				var label = c[\label];
				var w = try { label.bounds(font).width } { label.size * 6 };
				if(label.size > 0) {
					Pen.strokeColor = PlotLib.color(c[\colorKey]);
					Pen.width = PlotLib.lineWidth(2);
					Pen.line(Point(x, r.top - 9), Point(x + 12, r.top - 9));
					Pen.stroke;
					Pen.stringLeftJustIn(label, Rect(x + 16, r.top - 16, w + 8, 13),
						font, PlotLib.color(c[\colorKey]));
					x = x + 16 + w + 16;
				};
			};
		};

		if(marker.notNil) {
			var px = this.xPix(marker, r, b);
			Pen.strokeColor = PlotLib.color(\muted);
			Pen.width = PlotLib.lineWidth(1);
			Pen.line(Point(px, r.top), Point(px, r.bottom));
			Pen.stroke;
			curves.do { |c|
				var y = this.prYOf(this.class.mapWith(c[\spec], marker), r, b);
				Pen.fillColor = PlotLib.color(c[\colorKey]);
				Pen.addArc(Point(px, y), 3, 0, 2pi);
				Pen.fill;
			};
		};
	}
}
