/*
Scatter of xs against ys. The base for the bifurcation diagram and the phase
portrait, since both are a great many small points in a box.

	PltScatter(({ |i| i / 100 } ! 300), ({ |i| (i / 100).sin } ! 300)).front;
*/
PltScatter : PltView {
	var <xs, <ys;
	var dotColor, markerColor;
	var <>dotColorKey = \ink, <>markerColorKey = \gold;
	var <>dotSize = 1, <>alpha = 0.35, <marker;
	var <>equalAspect = false;
	var bounds;

	*new { |xs, ys, title = "scatter", width = 900, height = 460|
		^super.new(title, width, height).initPltScatter(xs, ys)
	}

	initPltScatter { |argXs, argYs|
		xLabel = "x"; yLabel = "y";
		this.data_(argXs, argYs);
	}

	data_ { |argXs, argYs|
		var xe, ye;
		xs = argXs ? #[]; ys = argYs ? #[];
		xe = PlotLib.extent(xs) ? #[0, 1];
		ye = PlotLib.extent(ys) ? #[0, 1];
		bounds = [xe[0], xe[1], ye[0], ye[1]];
		this.refresh;
		^this
	}

	// Fix the visible range instead of taking it from the data.
	limits_ { |xLo, xHi, yLo, yHi|
		bounds = [xLo, xHi, yLo, yHi];
		this.refresh;
		^this
	}

	// a vertical line saying "the parameter is here"; refreshes so it can be dragged
	marker_ { |value| marker = value; this.refresh; ^this }

	// colours resolve from the theme unless one was set explicitly, so a theme
	// change moves them and an override survives it
	dotColor { ^dotColor ?? { PlotLib.color(dotColorKey) } }
	dotColor_ { |c| dotColor = c; this.refresh; ^this }
	markerColor { ^markerColor ?? { PlotLib.color(markerColorKey) } }
	markerColor_ { |c| markerColor = c; this.refresh; ^this }

	dataBounds { ^bounds }

	/*
	With equalAspect the two axes get the same units per pixel, by widening the
	tighter range around its centre. An attractor is a shape, and Henon drawn
	with x spanning 2.6 and y spanning 0.4 in a square window is a shape that
	does not exist.
	*/
	adjustBounds { |b, r|
		var xSpan = b[1] - b[0], ySpan = b[3] - b[2], want, centre;
		if(equalAspect.not) { ^b };
		if((xSpan / r.width) > (ySpan / r.height)) {
			want = xSpan / r.width * r.height;
			centre = (b[2] + b[3]) / 2;
			^[b[0], b[1], centre - (want / 2), centre + (want / 2)]
		};
		want = ySpan / r.height * r.width;
		centre = (b[0] + b[1]) / 2;
		^[centre - (want / 2), centre + (want / 2), b[2], b[3]]
	}
	caption { ^xs.size.asString + "points" }

	drawData { |v, r, b|
		var n = xs.size.min(ys.size), d = dotSize;
		// one addRect per point but a single fill: filling per point is far slower
		Pen.fillColor = this.dotColor.copy.alpha_(PlotLib.alphaFor(alpha));
		n.do { |i|
			Pen.addRect(Rect(this.xPix(xs[i], r, b), this.yPix(ys[i], r, b), d, d));
		};
		Pen.fill;

		if(marker.notNil) {
			var px = this.xPix(marker, r, b);
			Pen.strokeColor = this.markerColor;
			Pen.width = PlotLib.lineWidth(1.5);
			Pen.line(Point(px, r.top), Point(px, r.bottom));
			Pen.stroke;
		};
	}
}
