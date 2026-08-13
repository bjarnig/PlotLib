/*
Abstract base for every plot: window, drawing area, grid, frame, ticks, caption.

A subclass provides
	dataBounds  -> [xLo, xHi, yLo, yHi] in data units, or nil for no ticks
	drawData    -> Pen calls, using xPix/yPix to place data
	caption     -> one line under the plot (optional)

Drawing order is grid, data, frame, ticks, labels, so the frame and the text
always stay on top of the data.
*/
PltView {
	var <window, <view;
	var <>padLeft = 54, <>padRight = 18, <>padTop = 18, <>padBottom = 44;
	var <>xLabel = "", <>yLabel = "", <>showTicks = true, <>xTicks = 6, <>yTicks = 5;

	*new { |title, width = 900, height = 460|
		^super.new.initPltView(title, width, height)
	}

	initPltView { |title, width, height|
		window = Window(title ? this.class.name.asString, Rect(140, 100, width, height))
			.background_(PlotLib.color(\bg));
		view = UserView(window, Rect(0, 0, width, height))
			.resize_(5)
			.background_(PlotLib.color(\bg))
			.drawFunc_({ |v| this.prDraw(v) });
		// registered so a theme change reaches every open window; removing it on
		// close is not optional, or the view is retained and keeps redrawing
		PlotLib.views.add(this);
		window.onClose_({ PlotLib.views.remove(this); this.free });
	}

	// Take the current theme: the backgrounds are set once, everything else is
	// read from the palette while drawing.
	applyTheme {
		{
			if(window.isClosed.not) {
				window.background_(PlotLib.color(\bg));
				view.background_(PlotLib.color(\bg));
				view.refresh;
			};
		}.defer;
		^this
	}

	front { window.front; ^this }
	close { window.close; ^this }
	refresh { { if(window.isClosed.not) { view.refresh } }.defer; ^this }
	// release responders, synths, dependants; the window's onClose calls it
	free { ^this }

	// The data area, inside the padding.
	plotRect { |v|
		var b = v.bounds;
		^Rect(padLeft, padTop,
			(b.width - padLeft - padRight).max(1),
			(b.height - padTop - padBottom).max(1))
	}

	xPix { |x, r, b| ^PlotLib.map(x, b[0], b[1], r.left, r.right) }
	yPix { |y, r, b| ^PlotLib.map(y, b[2], b[3], r.bottom, r.top) }

	dataBounds { ^nil }
	drawData { |v, r, b| ^this.subclassResponsibility(thisMethod) }
	caption { ^nil }

	// Write a PNG of the window as it stands. Call from an AppClock Routine and
	// wait first: drawFunc has not run when front returns.
	writeImage { |path|
		var image = Image.fromWindow(window);
		image.write(path.standardizePath);
		image.free;
		^path
	}

	// A last chance to change the visible range once the pixel size is known.
	adjustBounds { |b, r| ^b }

	prDraw { |v|
		var r = this.plotRect(v);
		var b = this.dataBounds;
		if(b.notNil) { b = this.adjustBounds(b, r) };
		this.drawPanel(r, b, { this.drawData(v, r, b) });
		this.prCaption(v, r);
	}

	// One framed, gridded, ticked box. Stacked views call it once per panel;
	// xLabels goes off on all but the lowest, whose labels they share.
	drawPanel { |r, b, func, xLabels = true, yLabels = true|
		var ticks = b.notNil and: { showTicks };
		if(ticks) { this.prGrid(r, b) };
		func.value;
		this.prFrame(r);
		if(ticks) { this.prTicks(r, b, xLabels, yLabels) };
	}

	/*
	Where the ticks go and what they say, in data units. A view with a
	transformed axis overrides these four and gets a matching grid, matching
	labels and no duplicated loops.
	*/
	xTickValues { |b| ^PlotLib.ticks(b[0], b[1], xTicks) }
	yTickValues { |b| ^PlotLib.ticks(b[2], b[3], yTicks) }
	xTickLabel { |value, b| ^PlotLib.fmt(value, b[1] - b[0]) }
	yTickLabel { |value, b| ^PlotLib.fmt(value, b[3] - b[2]) }

	prGrid { |r, b|
		Pen.strokeColor = PlotLib.color(\grid);
		Pen.width = PlotLib.lineWidth(1);
		this.xTickValues(b).do { |t|
			var px = this.xPix(t, r, b).round + 0.5;   // half pixel keeps 1px lines sharp
			Pen.line(Point(px, r.top), Point(px, r.bottom));
		};
		this.yTickValues(b).do { |t|
			var py = this.yPix(t, r, b).round + 0.5;
			Pen.line(Point(r.left, py), Point(r.right, py));
		};
		Pen.stroke;
	}

	prFrame { |r|
		Pen.strokeColor = PlotLib.color(\edge);
		Pen.width = PlotLib.lineWidth(1);
		Pen.addRect(Rect(r.left + 0.5, r.top + 0.5, r.width, r.height));
		Pen.stroke;
	}

	prTicks { |r, b, xLabels = true, yLabels = true|
		var font = PlotLib.font(9), col = PlotLib.color(\muted);
		if(xLabels) {
			this.xTickValues(b).do { |t|
				var px = this.xPix(t, r, b);
				Pen.stringCenteredIn(this.xTickLabel(t, b),
					Rect(px - 30, r.bottom + 5, 60, 12), font, col);
			};
		};
		if(yLabels) {
			this.yTickValues(b).do { |t|
				var py = this.yPix(t, r, b);
				Pen.stringRightJustIn(this.yTickLabel(t, b),
					Rect(r.left - padLeft, py - 6, padLeft - 7, 12), font, col);
			};
		};
	}

	prCaption { |v, r|
		var font = PlotLib.font(9), col = PlotLib.color(\muted);
		var line = this.caption;
		if(xLabel.size > 0) {
			Pen.stringCenteredIn(xLabel,
				Rect(r.left, r.bottom + 21, r.width, 12), font, col);
		};
		if(yLabel.size > 0) {
			Pen.stringAtPoint(yLabel, Point(6, padTop - 14), font, col);
		};
		if(line.notNil) {
			Pen.stringRightJustIn(line,
				Rect(r.left, r.bottom + 21, r.width, 12), font, col);
		};
	}
}
