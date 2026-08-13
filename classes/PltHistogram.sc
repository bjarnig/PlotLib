/*
Histogram of a collection of values.

	PltHistogram(({ 1.0.linrand } ! 4000), 0, 1, 40, "Pwhite").front;

*counts is the whole computation, so a distribution can be checked without a
window.
*/
PltHistogram : PltView {
	var <counts, <lo, <hi, <bins;
	var barColor;
	var <>barColorKey = \mint, <>alpha = 0.85, <>label;

	*new { |values, lo = 0, hi = 1, bins = 40, title = "histogram",
		width = 900, height = 420|
		^super.new(title, width, height).initPltHistogram(values, lo, hi, bins)
	}

	// Counts straight from data already binned elsewhere.
	*fromCounts { |counts, lo = 0, hi = 1, title = "histogram",
		width = 900, height = 420|
		^super.new(title, width, height).prSetCounts(counts, lo, hi)
	}

	initPltHistogram { |values, argLo, argHi, argBins|
		this.prSetCounts(PlotLib.histogram(values, argLo, argHi, argBins), argLo, argHi);
	}

	prSetCounts { |argCounts, argLo, argHi|
		counts = argCounts;
		lo = argLo; hi = argHi;
		bins = counts.size;
		xLabel = "value"; yLabel = "count";
		this.refresh;
		^this
	}

	data_ { |values|
		this.prSetCounts(PlotLib.histogram(values, lo, hi, bins), lo, hi);
		^this
	}

	// Proportion of the total in each bin; the shape a density is judged by.
	normalized {
		var total = counts.sum.max(1);
		^counts.collect { |c| c / total }
	}

	barColor { ^barColor ?? { PlotLib.color(barColorKey) } }
	barColor_ { |c| barColor = c; this.refresh; ^this }

	dataBounds { ^[lo, hi, 0, counts.maxItem.max(1)] }

	caption {
		var line = counts.sum.asString + "values," + bins.asString + "bins";
		^if(label.notNil) { line ++ "," + label } { line }
	}

	drawData { |v, r, b|
		var bw = r.width / bins;
		Pen.fillColor = this.barColor.copy.alpha_(PlotLib.alphaFor(alpha));
		counts.do { |c, i|
			// keep a non-empty bin at least one pixel tall, or rare values vanish
			var bh = if(c > 0) { ((c / b[3]) * r.height).max(1) } { 0 };
			if(bh > 0) {
				Pen.addRect(Rect(r.left + (i * bw), r.bottom - bh, (bw - 1).max(1), bh));
			};
		};
		Pen.fill;
	}
}
