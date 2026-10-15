/*
Self-similarity: every moment of a sound against every other, bright where they
are alike. Given two metrics, the square is split on its diagonal, one metric
below and one above, so the same sound is seen through both at once. What counts
as a return, and so as form, is decided by the features chosen.

A metric is \mfcc, \mel, or an Array of descriptor names from PltObservers.
*/
PltRecurrence : PltView {
	var <matrixA, <matrixB, <nameA, <nameB, <duration, <correlation;
	var image, ramp;
	var <>gamma = 1.5;

	// vectorsA, vectorsB: one feature vector per moment, evenly spread over
	// duration. Anything with a value per moment can be compared this way.
	*new { |vectorsA, vectorsB, duration = 1, nameA = "A", nameB = "B",
		title = "recurrence", width = 640, height = 630|
		^super.new(title, width, height)
			.initPltRecurrence(vectorsA, vectorsB, duration, nameA, nameB)
	}

	*fromSoundFile { |path, metricA = #[\centroid, \flatness], metricB = \mfcc,
		fftSize = 1024, points = 200, title, width = 640, height = 630|
		var a = PltSpectrogram.framesFromSoundFile(path, fftSize, 0.5);
		if(a.isNil) { ^nil };
		^this.new(
			this.features(a[\frames], metricA, a[\sampleRate], fftSize, points),
			metricB !? { this.features(a[\frames], metricB, a[\sampleRate], fftSize, points) },
			a[\duration], this.metricName(metricA), metricB !? { this.metricName(metricB) },
			title ?? { PathName(path).fileName }, width, height)
	}

	*metricName { |metric|
		^if(metric.isKindOf(Symbol)) { metric.asString } { metric.collect(_.asString).join(" + ") }
	}

	/*
	One vector per frame, pooled into points cells by their mean. Descriptors are
	z-scored first, or whichever has the biggest units would be the only one heard.
	SC's MFCC leaves out the 0th coefficient, and so does this.
	*/
	*features { |frames, metric, sampleRate, fftSize, points = 200|
		var vectors, cells, per;
		vectors = case
		{ metric == \mfcc or: { metric == \mel } } {
			var bank = PltUmwelt.melBank(sampleRate, frames[0].size, 40, 20,
				(sampleRate / 2).min(16000));
			frames.collect { |f|
				var e = PltUmwelt.melEnergies(f, bank);
				if(metric == \mel) { e } { PltUmwelt.dct(e, 13).drop(1) }
			}
		}
		{ true } {
			var prev, columns;
			var rows = frames.collect { |f|
				var row = metric.collect { |d| PltObservers.describe(d, f, sampleRate, fftSize, prev) };
				prev = f;
				row
			};
			columns = rows.flop.collect { |c|
				var m = c.mean, sd = c.stdDev.max(1e-12);
				c.collect { |x| (x - m) / sd }
			};
			columns.flop
		};
		per = vectors.size / points.min(vectors.size).max(1);
		cells = Array.fill(points.min(vectors.size), { |i|
			var group = vectors.copyRange((i * per).floor.asInteger,
				(((i + 1) * per).floor.asInteger - 1).max((i * per).floor.asInteger));
			group.sum / group.size
		});
		^cells
	}

	/*
	Similarity from Euclidean distance, as the rank of each distance among all of
	them: 1 for the nearest pair, 0 for the farthest. Two metrics then spend the
	same amount of brightness, and only where they put it differs.
	*/
	*matrix { |vectors|
		var n = vectors.size, pairs = Array.new, d = Array.new, out;
		// ranked once over i < j and mirrored, or a tie ranks i,j and j,i apart
		// not (i + 1 .. n - 1): on the last row that series counts down
		n.do { |i| (n - 1 - i).do { |k|
			var j = i + 1 + k;
			pairs = pairs.add([i, j]);
			d = d.add((vectors[i] - vectors[j]).squared.sum.sqrt);
		} };
		out = Array.fill(n, { 1.0 ! n });
		d.order.do { |index, rank|
			var s = 1 - (rank / (d.size - 1).max(1)), p = pairs[index];
			out[p[0]][p[1]] = s; out[p[1]][p[0]] = s;
		};
		^out
	}

	initPltRecurrence { |vectorsA, vectorsB, argDuration, argNameA, argNameB|
		var upper;
		matrixA = this.class.matrix(vectorsA);
		matrixB = vectorsB !? { this.class.matrix(vectorsB) };
		nameA = argNameA;
		nameB = argNameB;
		duration = argDuration;
		if(matrixB.notNil) {
			upper = { |m| m.collect { |row, i| row.copyToEnd(i + 1) }.flat };
			correlation = PltObservers.correlation(upper.(matrixA), upper.(matrixB));
		};
		xLabel = "seconds"; yLabel = "seconds";
		^this
	}

	// Square, whatever the window.
	plotRect { |v|
		var r = super.plotRect(v), side = r.width.min(r.height);
		^Rect(r.left, r.top, side, side)
	}

	dataBounds { ^[0, duration, 0, duration] }

	caption {
		^if(matrixB.isNil) { nameA } {
			"below: " ++ nameA ++ ",  above: " ++ nameB ++ ",  r = " ++ correlation.round(0.01)
		}
	}

	applyTheme { ramp = nil; image.free; image = nil; ^super.applyTheme }

	free { image.free; image = nil; ^this }

	prRamp {
		var steps = 128;
		^ramp ?? {
			ramp = Array.fill(steps, { |i|
				var t = i / (steps - 1);
				Image.colorToPixel(if(t < 0.5) {
					PlotLib.color(\bg).blend(PlotLib.color(\ink), t * 2)
				} {
					PlotLib.color(\ink).blend(PlotLib.color(\trace), (t - 0.5) * 2)
				})
			});
			ramp
		}
	}

	// Row 0 is the top, which is the end of the sound; below the diagonal is A.
	prImage {
		var n = matrixA.size, lut = this.prRamp, pixels;
		if(image.notNil) { ^image };
		pixels = Int32Array.fill(n * n, { |p|
			var row = p div: n, col = p % n, t = n - 1 - row;
			var m = if(matrixB.notNil and: { t > col }) { matrixB } { matrixA };
			lut[((m[t][col] ** gamma) * (lut.size - 1)).round.asInteger.clip(0, lut.size - 1)]
		});
		image = Image.newEmpty(n, n);
		image.setPixels(pixels, Rect(0, 0, n, n));
		^image
	}

	drawData { |v, r, b|
		var font = PlotLib.font(10), col = PlotLib.color(\gold);
		this.prImage.drawInRect(r, Rect(0, 0, matrixA.size, matrixA.size));
		if(matrixB.notNil) {
			Pen.strokeColor = col.copy.alpha_(PlotLib.alphaFor(0.5));
			Pen.width = PlotLib.lineWidth(1);
			Pen.line(r.leftBottom, r.rightTop);
			Pen.stroke;
			// on a ground patch: the matrix under them can be any brightness
			[[nameA, r.right - 8, r.bottom - 20, true], [nameB, r.left + 6, r.top + 6, false]].do { |a|
				var w = a[0].bounds(font).width + 10;
				var box = Rect(if(a[3]) { a[1] - w } { a[1] }, a[2], w, 15);
				Pen.fillColor = PlotLib.color(\bg).copy.alpha_(0.85);
				Pen.fillRect(box);
				Pen.stringCenteredIn(a[0], box, font, col);
			};
		};
	}
}
