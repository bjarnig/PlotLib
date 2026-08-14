/*
A sampled histogram with the density it is supposed to have drawn over it, so
"this looks exponential" becomes "this is, or is not, exponential".

The density may be any Function of x and need not be normalised: it is normalised
numerically over the plotted range, which is why a Beta shape works here without a
Gamma function anywhere.
*/
PltDistribution : PltHistogram {
	var <pdf, <expected;
	var <>pdfColorKey = \trace, <>showExpected = true;

	*new { |values, lo = 0, hi = 1, bins = 40, pdf, title = "distribution",
		width = 900, height = 420|
		^super.new(values, lo, hi, bins, title, width, height).prSetPdf(pdf)
	}

	/*
	Named densities, as unnormalised Functions of x.

	\logUniform is SuperCollider's exprand, whose density falls as 1/x. It is not
	the exponential distribution, whatever the name suggests, and this is the
	clearest place to see that.
	*/
	*density { |kind, params|
		var p = params ? #[];
		^switch(kind.asSymbol,
			\uniform, { { |x| 1 } },
			\logUniform, { { |x| 1 / x.max(1e-12) } },
			\exponential, { var lambda = p[0] ? 1; { |x| exp(lambda.neg * x) } },
			\gauss, {
				var mean = p[0] ? 0.5, dev = (p[1] ? 0.15).max(1e-9);
				{ |x| exp(((x - mean) / dev).squared * -0.5) }
			},
			\beta, {
				var a = p[0] ? 2, bb = p[1] ? 2;
				{ |x| (x.clip(1e-9, 1 - 1e-9) ** (a - 1)) * ((1 - x.clip(1e-9, 1 - 1e-9)) ** (bb - 1)) }
			},
			\triangular, { { |x| 1 - (2 * (x - 0.5).abs) } },
			{ ("PltDistribution: no density named" + kind).warn; { |x| 1 } }
		)
	}

	/*
	Expected counts per bin, normalised so the total matches the sample count.

	The density is averaged over each bin rather than read at its centre. A centre
	sample is wrong wherever the density is steep and hopeless where it is singular:
	1/x over a bin touching zero read 7000 against 3950 actually drawn.

	The range must be the distribution's own support. Plotting exprand(0.01, 1) over
	[0, 1] asks what the density does below 0.01, where no sample can land, and the
	end bins report nonsense.
	*/
	*expectedCounts { |pdfFunc, lo, hi, bins, total, subSamples = 16|
		var width = (hi - lo) / bins;
		var raw = Array.fill(bins, { |i|
			var left = lo + (i * width), sum = 0;
			subSamples.do { |k|
				sum = sum + pdfFunc.value(left + ((k + 0.5) / subSamples * width)).max(0)
			};
			sum / subSamples
		});
		var sum = raw.sum;
		if(sum <= 0) { ^0 ! bins };
		^raw.collect { |v| v / sum * total }
	}

	// Largest difference between the two as proportions of the total: 0 is a
	// perfect match, and anything past a few percent is visible in the picture.
	*deviation { |counts, expected|
		var total = counts.sum.max(1);
		^counts.size.collect { |i|
			((counts[i] - (expected[i] ? 0)) / total).abs
		}.maxItem
	}

	prSetPdf { |kindOrFunc, params|
		pdf = if(kindOrFunc.isKindOf(Function)) { kindOrFunc } {
			if(kindOrFunc.isNil) { nil } { this.class.density(kindOrFunc, params) }
		};
		this.prComputeExpected;
		this.refresh;
		^this
	}

	pdf_ { |kindOrFunc, params| ^this.prSetPdf(kindOrFunc, params) }

	prComputeExpected {
		expected = if(pdf.isNil) { nil } {
			this.class.expectedCounts(pdf, lo, hi, bins, counts.sum)
		};
	}

	data_ { |values|
		super.data_(values);
		this.prComputeExpected;
		^this
	}

	// How far the samples sit from the density, as a proportion of the total.
	deviation {
		^if(expected.isNil) { nil } { this.class.deviation(counts, expected) }
	}

	dataBounds {
		var top = counts.maxItem.max(1);
		if(expected.notNil) { top = max(top, expected.maxItem) };
		^[lo, hi, 0, top]
	}

	caption {
		var line = counts.sum.asString + "values," + bins.asString + "bins";
		if(label.notNil) { line = line ++ "," + label };
		if(expected.notNil) {
			line = line ++ ",  deviation" + (this.deviation * 100).round(0.1) ++ "%"
		};
		^line
	}

	drawData { |v, r, b|
		super.drawData(v, r, b);
		if(showExpected and: { expected.notNil }) {
			var bw = r.width / bins;
			Pen.strokeColor = PlotLib.color(pdfColorKey);
			Pen.width = PlotLib.lineWidth(1.5);
			expected.do { |e, i|
				// through the bin centres, which is where the density was sampled
				var p = Point(r.left + ((i + 0.5) * bw), this.yPix(e, r, b));
				if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		};
	}
}
