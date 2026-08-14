/*
A transition matrix as a heat map, with its stationary distribution under it and
the per state entropy beside it. Rows are the state moved from, columns the state
moved to.

A matrix is unreadable as numbers and obvious as a picture: where a chain gets
stuck, which states are dead ends, and how much freedom each one has.
*/
PltMarkov : PltView {
	var <matrix, <labels, <stationary, <entropies;
	var <>showValues = true, <>gamma = 0.8, <>stripHeight = 26;
	var ramp;

	*new { |matrix, labels, title = "markov", width = 560, height = 560|
		^super.new(title, width, height).initPltMarkov(matrix, labels)
	}

	/*
	Counted from a sequence of symbols. states fixes the order of the axes; left
	out, it is the symbols in order of first appearance.
	*/
	*fromSequence { |symbols, states, title = "markov", width = 560, height = 560|
		var order = states ?? { this.statesOf(symbols) };
		^this.new(this.count(symbols, order), order.collect(_.asString),
			title, width, height)
	}

	// The distinct symbols, in order of first appearance.
	*statesOf { |symbols|
		var out = [];
		symbols.do { |s| if(out.includesEqual(s).not) { out = out.add(s) } };
		^out
	}

	// Transition counts, normalised into probabilities.
	*count { |symbols, states|
		var n = states.size;
		var m = n.collect { 0 ! n };
		(symbols.size - 1).do { |i|
			var from = states.indexOfEqual(symbols[i]);
			var to = states.indexOfEqual(symbols[i + 1]);
			if(from.notNil and: { to.notNil }) { m[from][to] = m[from][to] + 1 };
		};
		^this.normalise(m)
	}

	// Rows summed to 1. A row of zeros is left alone: it is a dead end, and
	// spreading it into a uniform row would invent transitions that never happened.
	*normalise { |m|
		^m.collect { |row|
			var sum = row.sum;
			if(sum <= 0) { row.collect { 0 } } { row.collect { |v| v / sum } }
		}
	}

	/*
	The stationary distribution, by power iteration from a uniform start: where the
	chain spends its time in the long run.
	*/
	*stationary { |m, iterations = 400|
		var n = m.size;
		var p = (1 / n) ! n;
		iterations.do {
			var next = 0 ! n;
			n.do { |from|
				n.do { |to| next[to] = next[to] + (p[from] * (m[from][to] ? 0)) };
			};
			// a chain with dead ends loses mass, so renormalise or it decays to zero
			if(next.sum > 0) { p = next.collect { |v| v / next.sum } } { p = next };
		};
		^p
	}

	// Entropy of each row in bits: 0 is deterministic, log2(n) is a free choice.
	*entropy { |m|
		^m.collect { |row|
			row.inject(0, { |sum, p|
				sum + if(p > 0) { p.neg * log2(p) } { 0 }
			})
		}
	}

	initPltMarkov { |argMatrix, argLabels|
		matrix = this.class.normalise(argMatrix);
		labels = argLabels ?? { matrix.size.collect { |i| i.asString } };
		stationary = this.class.stationary(matrix);
		entropies = this.class.entropy(matrix);
		showTicks = false;      // the axes are named states, not numbers
		// the bottom holds the stationary bars, their labels and the caption
		padLeft = 44; padTop = 30; padBottom = 48; padRight = 74;
		^this
	}

	matrix_ { |argMatrix| ^this.initPltMarkov(argMatrix, labels).refresh }

	applyTheme { ramp = nil; ^super.applyTheme }

	prRamp {
		ramp ?? {
			ramp = Array.fill(64, { |i|
				var t = (i / 63) ** gamma;
				PlotLib.color(\bg).blend(PlotLib.color(\ink), t.min(1))
			});
		};
		^ramp
	}

	caption {
		^matrix.size.asString + "states,  mean entropy"
			+ entropies.mean.round(0.01) + "bits"
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var n = matrix.size;
		var grid = Rect(r.left, r.top, r.width, (r.height - stripHeight - 10).max(10));
		var cw = grid.width / n, ch = grid.height / n;
		var font = PlotLib.font(9), lut = this.prRamp;

		matrix.do { |row, i|
			row.do { |p, j|
				var cell = Rect(grid.left + (j * cw), grid.top + (i * ch), cw, ch);
				Pen.fillColor = lut[(p.clip(0, 1) * (lut.size - 1)).round.asInteger];
				Pen.fillRect(cell);
				if(showValues and: { cw > 30 } and: { p > 0.004 }) {
					Pen.stringCenteredIn(p.round(0.01).asString.keep(4), cell, font,
						// dark ink under a bright cell would vanish
						if(p > 0.55) { PlotLib.color(\bg) } { PlotLib.color(\muted) });
				};
			};
		};

		Pen.strokeColor = PlotLib.color(\edge);
		Pen.width = PlotLib.lineWidth(1);
		Pen.addRect(Rect(grid.left + 0.5, grid.top + 0.5, grid.width, grid.height));
		(1 .. n - 1).do { |k|
			Pen.line(Point(grid.left + (k * cw), grid.top),
				Point(grid.left + (k * cw), grid.bottom));
			Pen.line(Point(grid.left, grid.top + (k * ch)),
				Point(grid.right, grid.top + (k * ch)));
		};
		Pen.stroke;

		labels.do { |name, k|
			// columns above, rows to the left, so "from" and "to" are never confused
			Pen.stringCenteredIn(name,
				Rect(grid.left + (k * cw), grid.top - 15, cw, 12), font,
				PlotLib.color(\muted));
			Pen.stringRightJustIn(name,
				Rect(grid.left - padLeft, grid.top + (k * ch) + ((ch - 12) / 2),
					padLeft - 6, 12), font, PlotLib.color(\muted));
			Pen.stringLeftJustIn(entropies[k].round(0.01) + "b",
				Rect(grid.right + 6, grid.top + (k * ch) + ((ch - 12) / 2), 68, 12),
				font, PlotLib.color(\muted));
		};

		this.prDrawStationary(Rect(grid.left, grid.bottom + 10, grid.width, stripHeight),
			cw, font);

		Pen.stringLeftJustIn("to", Rect(grid.left, r.top - 29, grid.width, 12), font,
			PlotLib.color(\muted));
		Pen.stringLeftJustIn("bits", Rect(grid.right + 6, r.top - 15, 68, 12), font,
			PlotLib.color(\muted));
		this.prCaption(v, r);
	}

	// The stationary distribution, aligned under the columns it belongs to. Scaled
	// against twice uniform, not against its own peak, or a flat distribution draws
	// as four full bars and reads as though it were concentrated.
	prDrawStationary { |r, cw, font|
		var peak = max(stationary.maxItem, 2 / stationary.size.max(1)).max(1e-9);
		stationary.do { |p, k|
			var h = (p / peak * r.height).max(1);
			Pen.fillColor = PlotLib.color(\mint).copy.alpha_(PlotLib.alphaFor(0.8));
			Pen.fillRect(Rect(r.left + (k * cw) + 2, r.bottom - h, cw - 4, h));
			if(cw > 30) {
				Pen.stringCenteredIn(p.round(0.01).asString.keep(4),
					Rect(r.left + (k * cw), r.bottom + 2, cw, 12), font,
					PlotLib.color(\muted));
			};
		};
		Pen.stringLeftJustIn("stationary",
			Rect(r.left, r.top - 13, r.width, 12), font, PlotLib.color(\muted));
	}
}
