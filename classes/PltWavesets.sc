/*
Wavesets: the signal cut at upward zero crossings, with the pitch and peak of each
one over time, and a chosen waveset drawn large below.

The segmentation follows the Wavesets quark exactly, so the two agree: a crossing
is a move from non-positive to positive, a waveset runs from one crossing to the
next, and minLength suppresses the ones too short to be a cycle.
*/
PltWavesets : PltView {
	var <xings, <fracXings, <lengths, <amps;
	var <signal, <sampleRate, <duration;
	var <selected = 0;
	// the gap holds the time tick row, which needs more than a text height
	var <>minLength = 10, <>panelGap = 20;
	var <>pitchColorKey = \ink, <>ampColorKey = \mint, <>traceColorKey = \trace;
	var <>minHz = 20, <>maxHz = 5000;
	var panelAxis = \linear;      // \logHz while the pitch panel is drawing

	*new { |signal, sampleRate = 48000, minLength = 10, title = "wavesets",
		width = 900, height = 560|
		^super.new(title, width, height)
			.initPltWavesets(signal, sampleRate, minLength)
	}

	// One channel of a soundfile. maxFrames caps what is held for the detail panel.
	*fromSoundFile { |path, channel = 0, maxFrames = 4000000, minLength = 10,
		title, width = 900, height = 560|
		var sf = SoundFile.openRead(path.standardizePath);
		var data, chans, frames, mono;
		if(sf.isNil) { ("PltWavesets: cannot read" + path).warn; ^nil };
		chans = sf.numChannels;
		frames = sf.numFrames.min(maxFrames);
		data = FloatArray.newClear(frames * chans);
		sf.readData(data);
		mono = if(chans == 1) { data } {
			FloatArray.newFrom(Array.fill(data.size div: chans, { |f|
				data[(f * chans) + channel.min(chans - 1)]
			}))
		};
		^this.new(mono, sf.sampleRate, minLength,
			title ?? { PathName(path).fileName }, width, height).prCloseFile(sf)
	}

	/*
	The wavesets of a signal: xings, fracXings, lengths and amps, named as the
	Wavesets quark names them.

	fracXings interpolate the crossing between the two samples that straddle it, so
	a length is not quantised to whole samples. amps is the peak magnitude of each
	waveset, positive or negative, whichever is larger.
	*/
	*segments { |signal, minLength = 10|
		var xs = Array.new, fracs = Array.new, lens = Array.new, peaks = Array.new;
		var count = 0, prev = 0.0, maxSamp = 0.0, minSamp = 0.0;
		signal.size.do { |i|
			var cur = signal[i];
			if((prev <= 0.0) and: { cur > 0.0 } and: { count >= minLength }) {
				if(xs.notEmpty) {
					peaks = peaks.add(max(maxSamp, minSamp.abs));
					lens = lens.add(count);
				};
				xs = xs.add(i);
				fracs = fracs.add(i - 1 + (prev / (prev - cur)));
				maxSamp = 0.0; minSamp = 0.0; count = 0;
			};
			count = count + 1;
			if(cur > maxSamp) { maxSamp = cur };
			if(cur < minSamp) { minSamp = cur };
			prev = cur;
		};
		^(xings: xs, fracXings: fracs, lengths: lens, amps: peaks)
	}

	// The pitch each waveset implies, in Hz. A waveset is one cycle by definition,
	// so its length is a period.
	*frequencies { |lengths, sampleRate = 48000|
		^lengths.collect { |n| sampleRate / n.max(1) }
	}

	initPltWavesets { |argSignal, argSampleRate, argMinLength|
		var seg;
		signal = argSignal;
		sampleRate = argSampleRate;
		minLength = argMinLength;
		duration = signal.size / sampleRate;
		seg = this.class.segments(signal, minLength);
		xings = seg[\xings]; fracXings = seg[\fracXings];
		lengths = seg[\lengths]; amps = seg[\amps];
		selected = (lengths.size div: 2).max(0);
		// no x label: the top panels are in seconds and the detail panel is in
		// samples, and a word on the shared row collides with the last tick
		xLabel = "";
		^this
	}

	prCloseFile { |sf| sf.close; ^this }

	// Which waveset is drawn large, by index or by time.
	select { |index| selected = index.clip(0, (lengths.size - 1).max(0)); this.refresh; ^this }

	selectAt { |seconds|
		var frame = seconds * sampleRate;
		^this.select(xings.indexOfGreaterThan(frame) ?? { lengths.size - 1 })
	}

	frequencies { ^this.class.frequencies(lengths, sampleRate) }

	// The samples of one waveset.
	wavesetAt { |index|
		var from, to;
		if(lengths.size == 0) { ^[0] };
		index = index.clip(0, lengths.size - 1);
		from = xings[index];
		to = (xings[index + 1] ? signal.size).min(signal.size);
		^signal.copyRange(from, (to - 1).max(from))
	}

	caption {
		var freqs = this.frequencies;
		if(lengths.size == 0) { ^"no wavesets" };
		^lengths.size.asString + "wavesets, mean" + lengths.mean.round(0.1)
			++ " samples (" ++ (sampleRate / lengths.mean).round(1).asInteger ++ " Hz),"
			+ "selected" + selected + "at" + (xings[selected] / sampleRate).round(0.001) ++ " s"
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var h = (r.height - (panelGap * 2)) / 3;
		var pitchRect = Rect(r.left, r.top, r.width, h);
		var ampRect = Rect(r.left, r.top + h + panelGap, r.width, h);
		var detailRect = Rect(r.left, r.top + ((h + panelGap) * 2), r.width, h);
		var timeBounds = [0, duration];
		var freqs = this.frequencies;

		panelAxis = \logHz;
		this.drawPanel(pitchRect,
			[0, duration, minHz.max(1).log10, maxHz.log10],
			{ this.prDrawPitch(pitchRect, [0, duration, minHz.max(1).log10, maxHz.log10],
				freqs) },
			false, true);
		panelAxis = \linear;
		Pen.stringLeftJustIn(" Hz per waveset",
			Rect(pitchRect.left, pitchRect.top + 3, pitchRect.width, 12),
			PlotLib.font(9), PlotLib.color(\muted));

		this.drawPanel(ampRect, [0, duration, 0, 1],
			{ this.prDrawAmps(ampRect, [0, duration, 0, 1]) }, true, true);
		Pen.stringLeftJustIn(" peak per waveset",
			Rect(ampRect.left, ampRect.top + 3, ampRect.width, 12),
			PlotLib.font(9), PlotLib.color(\muted));

		this.prDrawDetail(detailRect);
		this.prCaption(v, r);
	}

	// Log frequency on the pitch panel only: waveset pitch spans octaves, and a
	// linear axis buries everything below a few hundred Hz.
	yTickValues { |b|
		if(panelAxis == \logHz) { ^PlotLib.freqTicks(minHz, maxHz).collect(_.log10) };
		^super.yTickValues(b)
	}

	yTickLabel { |value, b|
		if(panelAxis == \logHz) { ^PlotLib.freqLabel(10 ** value) };
		^super.yTickLabel(value, b)
	}

	prDrawPitch { |r, b, freqs|
		Pen.fillColor = PlotLib.color(pitchColorKey).copy.alpha_(PlotLib.alphaFor(0.7));
		freqs.do { |f, i|
			var px = this.xPix(xings[i] / sampleRate, r, b);
			var py = this.yPix(f.clip(minHz, maxHz).log10, r, b);
			Pen.addRect(Rect(px, py, 1.5, 1.5));
		};
		Pen.fill;
		this.prMarkSelected(r);
	}

	prDrawAmps { |r, b|
		Pen.strokeColor = PlotLib.color(ampColorKey);
		Pen.width = PlotLib.lineWidth(1);
		amps.do { |a, i|
			var px = this.xPix(xings[i] / sampleRate, r, b);
			Pen.line(Point(px, r.bottom), Point(px, this.yPix(a.min(1), r, b)));
		};
		Pen.stroke;
		this.prMarkSelected(r);
	}

	prMarkSelected { |r|
		var px;
		if(lengths.size == 0) { ^this };
		px = this.xPix(xings[selected] / sampleRate, r, [0, duration]);
		Pen.strokeColor = PlotLib.color(\gold);
		Pen.width = PlotLib.lineWidth(1);
		Pen.line(Point(px, r.top), Point(px, r.bottom));
		Pen.stroke;
	}

	// The chosen waveset at its own scale, which is the point of the whole view:
	// a waveset is a shape, and the shapes differ wildly within one sound.
	prDrawDetail { |r|
		var ws = this.wavesetAt(selected);
		var b = [0, (ws.size - 1).max(1), -1, 1];
		this.drawPanel(r, b, {
			Pen.strokeColor = PlotLib.color(traceColorKey);
			Pen.width = PlotLib.lineWidth(1.2);
			ws.do { |y, i|
				var p = Point(this.xPix(i, r, b), this.yPix(y.clip(-1, 1), r, b));
				if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		}, true, true);
		Pen.stringLeftJustIn(" waveset" + selected ++ ":" + ws.size + "samples,"
			+ (sampleRate / ws.size.max(1)).round(1).asInteger ++ " Hz",
			Rect(r.left, r.top + 3, r.width, 12), PlotLib.font(9), PlotLib.color(\muted));
	}
}
