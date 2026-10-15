/*
Several observers on one sound: each a descriptor at an analysis size, on one clock
and normalised to its own range, with the others drawn faintly behind it and their
spread as the last panel. Where the observers part, each one's world shows.

Frames below floorDb are shaded: every descriptor still reports a value there.
*/
PltObservers : PltView {
	var <names, <details, <times, <tracks, <spread, <silent, <duration, <agreement;
	var <>floorDb = -50, <>panelGap = 6, <>colorKeys = #[\ink, \mint, \gold, \rose, \trace];

	// values: an Array of Arrays, each spread evenly over duration. Any analyser
	// can be compared this way, including one run outside SuperCollider.
	*new { |values, duration = 1, names, title = "observers", width = 900, height = 560|
		var points = values.collect(_.size).maxItem.max(2);
		var grid = this.grid(points, duration);
		var data = (
			names: names ?? { values.collect { |v, i| "observer" + (i + 1) } },
			details: values.collect { "" },
			duration: duration,
			times: grid,
			raw: values.collect { |v|
				this.resample(v, Array.fill(v.size, { |i| (i + 0.5) / v.size * duration }), grid)
			},
			level: nil
		);
		^super.new(title, width, height).initPltObservers(data)
	}

	*fromSoundFile { |path, observers, hop = 0.5, points = 600, title,
		width = 900, height = 560|
		var data = this.analyse(path, observers ?? { this.defaultObservers }, hop, points);
		if(data.isNil) { ^nil };
		^super.new(title ?? { PathName(path).fileName }, width, height)
			.initPltObservers(data)
	}

	// One descriptor at several analysis sizes: the analyser sweep.
	*sweep { |path, descriptor = \centroid, sizes = #[256, 1024, 4096, 16384],
		hop = 0.5, points = 600, title, width = 900, height = 560|
		^this.fromSoundFile(path,
			sizes.collect { |n| (descriptor: descriptor, fftSize: n) },
			hop, points, title, width, height)
	}

	*defaultObservers {
		^[
			(descriptor: \centroid, fftSize: 1024),
			(descriptor: \rolloff, fftSize: 1024),
			(descriptor: \peak, fftSize: 4096),
			(descriptor: \flatness, fftSize: 1024)
		]
	}

	// Frames are computed once per FFT size, then every observer is resampled to
	// one grid so they can be read through one another.
	*analyse { |path, observers, hop = 0.5, points = 600|
		var cache = (), first, sampleRate, duration, grid, level;
		observers = observers.collect { |o|
			if(o.isKindOf(Symbol)) { (descriptor: o) } { o }
		};
		observers.do { |o|
			var n = o[\fftSize] ? 1024;
			if(cache[n].isNil) {
				var a = PltSpectrogram.framesFromSoundFile(path, n, hop);
				if(a.isNil) { ^nil };
				cache[n] = a;
			};
		};
		first = cache[observers[0][\fftSize] ? 1024];
		sampleRate = first[\sampleRate];
		duration = first[\duration];
		grid = this.grid(points, duration);
		level = this.resample(
			first[\frames].collect { |f| this.describe(\level, f) },
			this.frameTimes(first[\frames].size, observers[0][\fftSize] ? 1024, hop, sampleRate),
			grid);
		^(
			names: observers.collect { |o|
				o[\name] ?? { o[\descriptor].asString + "·" + (o[\fftSize] ? 1024) }
			},
			details: observers.collect { |o|
				var n = o[\fftSize] ? 1024;
				PlotLib.fmt(sampleRate / n, 100) ++ " Hz per bin, "
					++ PlotLib.fmt(n / sampleRate * 1000, 100) ++ " ms window"
			},
			duration: duration,
			times: grid,
			level: level,
			raw: observers.collect { |o|
				var n = o[\fftSize] ? 1024, frames = cache[n][\frames], prev;
				var func = o[\func] ?? { { |frame, sr, size, before|
					this.describe(o[\descriptor], frame, sr, size, before)
				} };
				var values = frames.collect { |frame|
					var v = func.value(frame, sampleRate, n, prev);
					prev = frame;
					v
				};
				this.resample(values, this.frameTimes(frames.size, n, hop, sampleRate), grid)
			}
		)
	}

	*grid { |points, duration| ^Array.fill(points, { |i| (i + 0.5) / points * duration }) }

	// A frame is named by the middle of its window, not its start.
	*frameTimes { |count, fftSize, hop, sampleRate|
		var step = (fftSize * hop).asInteger.max(1);
		^Array.fill(count, { |i| ((i * step) + (fftSize / 2)) / sampleRate })
	}

	/*
	One number from one frame of magnitudes. Powers, not magnitudes, for flatness
	and rolloff, as SpecFlatness and SpecPcile do. A silent frame gives 0: the
	descriptor has nothing to say and says something anyway.
	*/
	*describe { |descriptor, frame, sampleRate = 48000, fftSize = 1024, prev|
		var binHz = sampleRate / fftSize;
		^switch(descriptor)
		{ \centroid } {
			var num = 0, den = 0;
			frame.do { |m, k| num = num + (m * k * binHz); den = den + m };
			if(den > 0) { num / den } { 0 }
		}
		{ \spread } {
			var c = this.describe(\centroid, frame, sampleRate, fftSize), num = 0, den = 0;
			frame.do { |m, k| num = num + (m * ((k * binHz) - c).squared); den = den + m };
			if(den > 0) { (num / den).sqrt } { 0 }
		}
		{ \rolloff } {
			var total = 0, acc = 0, k = 0;
			frame.do { |m| total = total + (m * m) };
			if(total <= 0) { 0 } {
				while({ (k < (frame.size - 1)) and: { (acc + frame[k].squared) < (total * 0.85) } }, {
					acc = acc + frame[k].squared;
					k = k + 1;
				});
				k * binHz
			}
		}
		{ \flatness } {
			var logSum = 0, sum = 0, n = frame.size;
			frame.do { |m| var p = (m * m) + 1e-20; logSum = logSum + p.log; sum = sum + p };
			(logSum / n).exp / (sum / n)
		}
		{ \peak } {
			// bin 0 is DC, never a pitch
			var best = 1;
			(1 .. frame.size - 1).do { |k| if(frame[k] > frame[best]) { best = k } };
			if(frame[best] > 0) { best * binHz } { 0 }
		}
		{ \flux } {
			var sum = 0;
			if(prev.notNil) {
				frame.size.min(prev.size).do { |k|
					var d = frame[k] - prev[k];
					if(d > 0) { sum = sum + d };
				};
			};
			sum
		}
		{ \level } { PlotLib.ampDb(frame.maxItem ? 0, -120) }
		{ ("PltObservers: no descriptor" + descriptor).warn; 0 }
	}

	// Mean of the values falling in each grid cell, the nearest one where none do,
	// so a coarse observer keeps its steps and a fine one keeps its spikes.
	*resample { |values, from, to|
		var j = 0, last = values.size - 1;
		var half = if(to.size > 1) { (to[1] - to[0]) / 2 } { inf };
		if(values.size == 0) { ^to.collect { 0 } };
		^to.collect { |t|
			var sum = 0, count = 0, k;
			while({ (j < last) and: { from[j] < (t - half) } }, { j = j + 1 });
			k = j;
			while({ (k <= last) and: { from[k] < (t + half) } }, {
				sum = sum + values[k]; count = count + 1; k = k + 1;
			});
			if(count > 0) { sum / count } {
				// nearest: j is the first frame at or after the cell, or the last
				if((j > 0) and: { (t - from[j - 1]).abs < (from[j] - t).abs }) {
					values[j - 1]
				} { values[j] }
			}
		}
	}

	// To 0..1 between two percentiles, so one wild frame does not flatten the rest.
	*normalise { |values, lo = 0.02, hi = 0.98|
		var a = this.percentile(values, lo), b = this.percentile(values, hi);
		if(b <= a) { ^values.collect { 0.5 } };
		^values.collect { |v| ((v - a) / (b - a)).clip(0, 1) }
	}

	*percentile { |values, p|
		var sorted = values.copy.sort;
		if(sorted.size == 0) { ^0 };
		^sorted[(p * (sorted.size - 1)).round.asInteger]
	}

	*correlation { |a, b|
		var ma = a.mean, mb = b.mean, num = 0, da = 0, db = 0;
		a.size.min(b.size).do { |i|
			var x = a[i] - ma, y = b[i] - mb;
			num = num + (x * y); da = da + (x * x); db = db + (y * y);
		};
		if((da <= 0) or: { db <= 0 }) { ^0 };
		^num / (da * db).sqrt
	}

	// Mean correlation over every pair of tracks.
	*agreement { |tracks|
		var sum = 0, count = 0;
		tracks.do { |a, i|
			tracks.do { |b, j|
				if(j > i) { sum = sum + this.correlation(a, b); count = count + 1 };
			};
		};
		^if(count > 0) { sum / count } { 1 }
	}

	initPltObservers { |data|
		names = data[\names];
		details = data[\details];
		duration = data[\duration];
		times = data[\times];
		tracks = data[\raw].collect { |v| this.class.normalise(v) };
		spread = times.collect { |t, i| tracks.collect { |tr| tr[i] }.stdDev };
		silent = if(data[\level].isNil) { times.collect { false } } {
			data[\level].collect { |db| db < floorDb }
		};
		agreement = this.class.agreement(tracks);
		xLabel = "seconds";
		padLeft = 18;
		yTicks = 2;
		^this
	}

	caption {
		^"mean agreement r =" + agreement.round(0.01) ++ ", shaded below" + floorDb + "dB"
	}

	// Flags as [start, end) runs, so a shaded stretch is one rect rather than hundreds.
	*runs { |flags|
		var runs = Array.new, start;
		flags.do { |f, i|
			if(f and: { start.isNil }) { start = i };
			if(f.not and: { start.notNil }) { runs = runs.add([start, i]); start = nil };
		};
		if(start.notNil) { runs = runs.add([start, flags.size]) };
		^runs
	}

	prShade { |r, b, flags, color|
		var cell = duration / times.size.max(1);
		Pen.fillColor = color;
		this.class.runs(flags).do { |run|
			var x0 = this.xPix(run[0] * cell, r, b), x1 = this.xPix(run[1] * cell, r, b);
			Pen.addRect(Rect(x0, r.top, (x1 - x0).max(1), r.height));
		};
		Pen.fill;
	}

	prLine { |values, r, b, color, width|
		Pen.strokeColor = color;
		Pen.width = PlotLib.lineWidth(width);
		values.do { |v, i|
			var p = Point(this.xPix(times[i], r, b), this.yPix(v, r, b));
			if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var n = tracks.size + 1;
		var h = (r.height - (panelGap * (n - 1))) / n;
		var b = [0, duration, 0, 1];
		var high = PltObservers.percentile(spread, 0.9);
		var parted = spread.collect { |s| (s >= high) and: { s > 0 } };
		var font = PlotLib.font(9), muted = PlotLib.color(\muted);
		var sb, last;

		tracks.do { |track, i|
			var panel = Rect(r.left, r.top + (i * (h + panelGap)), r.width, h);
			var col = PlotLib.color(colorKeys.wrapAt(i));
			this.drawPanel(panel, b, {
				this.prShade(panel, b, silent, muted.copy.alpha_(PlotLib.alphaFor(0.12)));
				this.prShade(panel, b, parted,
					PlotLib.color(\gold).copy.alpha_(PlotLib.alphaFor(0.1)));
				tracks.do { |other, j|
					if(j != i) {
						this.prLine(other, panel, b, muted.copy.alpha_(PlotLib.alphaFor(0.3)), 0.8)
					}
				};
				this.prLine(track, panel, b, col, 1.3);
			}, false, false);
			Pen.stringLeftJustIn(" " ++ names[i] ++ if(showText and: { details[i].size > 0 }) {
				"   " ++ details[i]
			} { "" }, Rect(panel.left + 2, panel.top + 3, panel.width, 12), font, col);
		};

		sb = [0, duration, 0, spread.maxItem.max(1e-9)];
		last = Rect(r.left, r.top + (tracks.size * (h + panelGap)), r.width, h);
		this.drawPanel(last, sb, {
			Pen.fillColor = PlotLib.color(\gold).copy.alpha_(PlotLib.alphaFor(0.7));
			Pen.moveTo(Point(last.left, last.bottom));
			spread.do { |s, i|
				Pen.lineTo(Point(this.xPix(times[i], last, sb), this.yPix(s, last, sb)))
			};
			Pen.lineTo(Point(last.right, last.bottom));
			Pen.fill;
		}, true, false);
		Pen.stringLeftJustIn(if(showText) { " disagreement: spread across the observers" } { " disagreement" },
			Rect(last.left + 2, last.top + 3, last.width, 12), font, PlotLib.color(\gold));

		this.prCaption(v, r);
	}
}
