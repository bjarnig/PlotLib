/*
The onset threshold swept: one detection function, and the events it yields at
every threshold from lo to hi, one row each. An event that persists down the rows
is in the signal; one that flickers in and out belongs to the cut.

Above the raster, the curve at the chosen cut: the part above it in ink, the part
below it, which the detector discards, muted. Click or drag in the raster to move
the cut.
*/
PltCut : PltView {
	var <values, <frameRate, <offset, <thresholds, <rows, <duration;
	var <threshold = 0.25, <minGap = 0.05, <>countWidth = 64, <>curveShare = 0.3;

	// values: any detection function, 0 to 1, at frameRate.
	*new { |values, frameRate = 100, steps = 48, lo = 0.02, hi = 0.95,
		title = "cut", width = 900, height = 540|
		^super.new(title, width, height).initPltCut(values, frameRate, 0, steps, lo, hi)
	}

	// Spectral novelty from a file, the same curve PltNovelty draws.
	*fromSoundFile { |path, fftSize = 1024, hop = 0.25, steps = 48, lo = 0.02, hi = 0.95,
		title, width = 900, height = 540|
		var a = PltSpectrogram.framesFromSoundFile(path, fftSize, hop);
		if(a.isNil) { ^nil };
		^super.new(title ?? { PathName(path).fileName }, width, height)
			.initPltCut(PltNovelty.flux(a[\frames]),
				a[\frames].size / a[\duration].max(1e-9),
				fftSize / 2 / a[\sampleRate], steps, lo, hi)
	}

	// The peaks at every threshold: an Array of frame-index Arrays, one per threshold.
	*sweep { |values, thresholds, minGapFrames = 3|
		^thresholds.collect { |t| PltNovelty.peaks(values, t, minGapFrames) }
	}

	// The share of the curve's total that lies below the cut.
	*outside { |values, threshold|
		var below = 0, total = 0;
		values.do { |v| below = below + v.min(threshold); total = total + v };
		^if(total > 0) { below / total } { 0 }
	}

	initPltCut { |argValues, argRate, argOffset, steps, lo, hi|
		values = argValues;
		frameRate = argRate;
		offset = argOffset;
		duration = values.size / frameRate.max(1e-9);
		thresholds = Array.fill(steps, { |i| lo + ((hi - lo) * i / (steps - 1).max(1)) });
		this.prSweep;
		xLabel = "seconds";
		padRight = countWidth + 18;
		yTicks = 6;
		view.mouseDownAction_({ |v, x, y| this.prMouse(v, y) })
			.mouseMoveAction_({ |v, x, y| this.prMouse(v, y) });
		^this
	}

	gapFrames { ^(minGap * frameRate).round.max(1) }

	prSweep { rows = this.class.sweep(values, thresholds, this.gapFrames); ^this }

	minGap_ { |seconds| minGap = seconds; this.prSweep; this.refresh; ^this }

	threshold_ { |value| threshold = value; this.refresh; ^this }

	// The events at the chosen cut, in seconds, at the middle of their window.
	onsetTimes {
		^PltNovelty.peaks(values, threshold, this.gapFrames)
			.collect { |i| (i / frameRate.max(1e-9)) + offset }
	}

	prLayout { |r|
		var top = r.height * curveShare;
		^[Rect(r.left, r.top, r.width, top - 8), Rect(r.left, r.top + top + 8, r.width, r.height - top - 8)]
	}

	prMouse { |v, y|
		var raster = this.prLayout(this.plotRect(v))[1];
		var t = PlotLib.map(y, raster.bottom, raster.top, thresholds.first, thresholds.last);
		this.threshold_(t.round(0.005));
	}

	caption {
		^"cut at" + threshold.round(0.01) ++ ":" + this.onsetTimes.size + "events,"
			+ (this.class.outside(values, threshold) * 100).round.asInteger ++ "% of the curve below it"
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var layout = this.prLayout(r), curve = layout[0], raster = layout[1];
		var cb = [0, duration, 0, 1], rb = [0, duration, thresholds.first, thresholds.last];
		var rowH = raster.height / thresholds.size;
		var maxCount = rows.collect(_.size).maxItem.max(1);
		var font = PlotLib.font(9), muted = PlotLib.color(\muted);
		var gold = PlotLib.color(\gold), ink = PlotLib.color(\ink);
		var barW = (curve.width / values.size.max(1)).max(1);
		var picked = this.onsetTimes;

		this.drawPanel(curve, cb, {
			values.do { |value, i|
				var px = this.xPix(i / frameRate, curve, cb);
				var cut = this.yPix(threshold.min(value), curve, cb);
				Pen.fillColor = muted.copy.alpha_(PlotLib.alphaFor(0.45));
				Pen.fillRect(Rect(px, cut, barW, curve.bottom - cut));
				if(value > threshold) {
					var top = this.yPix(value.clip(0, 1), curve, cb);
					Pen.fillColor = ink;
					Pen.fillRect(Rect(px, top, barW, cut - top));
				};
			};
			Pen.strokeColor = gold;
			Pen.width = PlotLib.lineWidth(1);
			Pen.line(Point(curve.left, this.yPix(threshold, curve, cb)),
				Point(curve.right, this.yPix(threshold, curve, cb)));
			Pen.stroke;
			Pen.width = PlotLib.lineWidth(1.5);
			picked.do { |t|
				var px = this.xPix(t, curve, cb);
				Pen.line(Point(px, curve.top), Point(px, curve.top + (curve.height * 0.12)));
			};
			Pen.stroke;
		}, false, false);
		if(showText) {
			Pen.stringLeftJustIn("detection function, cut at " ++ threshold.round(0.01),
				Rect(curve.left, curve.top - 14, curve.width, 12), font, muted);
		};

		this.drawPanel(raster, rb, {
			Pen.width = PlotLib.lineWidth(1.2);
			rows.do { |row, i|
				var y1 = raster.bottom - (i * rowH), y0 = y1 - rowH;
				var col = if((thresholds[i] - threshold).abs < ((rb[3] - rb[2]) / thresholds.size / 2)) {
					gold
				} { ink.copy.alpha_(PlotLib.alphaFor(0.8)) };
				Pen.strokeColor = col;
				row.do { |frame|
					var px = this.xPix((frame / frameRate) + offset, raster, rb);
					Pen.line(Point(px, y0 + 1), Point(px, y1 - 1));
				};
				Pen.stroke;
				// how many events this cut makes, in the right margin
				Pen.fillColor = muted.copy.alpha_(PlotLib.alphaFor(0.6));
				Pen.fillRect(Rect(raster.right + 8, y0 + 1,
					(row.size / maxCount) * countWidth, (rowH - 1).max(1)));
			};
			Pen.strokeColor = gold;
			Pen.width = PlotLib.lineWidth(1);
			Pen.line(Point(raster.left, this.yPix(threshold, raster, rb)),
				Point(raster.right, this.yPix(threshold, raster, rb)));
			Pen.stroke;
		}, true, true);
		if(showText) {
			Pen.stringLeftJustIn("the events at every threshold",
				Rect(raster.left, raster.top - 14, raster.width, 12), font, muted);
			Pen.stringLeftJustIn(maxCount.asString + "events",
				Rect(raster.right + 8, raster.top - 14, countWidth + 10, 12), font, muted);
		};

		this.prCaption(v, r);
	}
}
