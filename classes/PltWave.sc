/*
Waveform of recorded material: one vertical line per pixel column, from the
minimum to the maximum sample in that column, with the RMS drawn inside it.

	PltWave(Signal.sineFill(2048, [1, 0, 0.3]), 48000).front;
	PltWave.fromSoundFile(Platform.resourceDir +/+ "sounds/a11wlk01.wav").front;

Signal:plot draws every sample, which is right for 512 samples and unusable for
five million. A min and max per column stays legible at any length and never
drops a peak, which a line through every Nth sample does.

Multichannel material gets one panel per channel, stacked, sharing the time axis.
*/
PltWave : PltView {
	// one Event per channel: (mins:, maxs:, rms:)
	var <channels;
	var <duration, <sampleRate, <numChannels, <stride = 1;
	var <>traceColorKey = \trace, <>rmsColorKey = \ink;
	// dbScale has its own setter below, so it is declared getter-only here
	var <dbScale = false, <>floorDb = -66, <>showRms = true, <>panelGap = 12;
	var source;                    // (path:) or (data:), for zoom

	/*
	data is a collection of numbers for one channel, or a collection of those for
	several: PltWave([left, right], 48000).
	*/
	*new { |data, sampleRate = 48000, title = "wave", columns = 900,
		width = 900, height = 300|
		^super.new(title, width, height).initPltWave(data, sampleRate, columns)
	}

	// Reads in chunks, so a long file never has to be in memory at once.
	*fromSoundFile { |path, columns = 900, title, width = 900, height = 300|
		var env = this.envelopeFromSoundFile(path, columns);
		if(env.isNil) { ^nil };
		^super.new(title ?? { PathName(path).fileName }, width, height)
			.prSetEnvelope(env)
			.prSetSource((path: path.standardizePath))
	}

	// Server side buffer, so this one answers through an action.
	*fromBuffer { |buffer, columns = 900, title, width = 900, height = 300, action|
		buffer.loadToFloatArray(action: { |data|
			var frames, chans = buffer.numChannels;
			// interleaved, so split it back out per channel
			frames = data.size div: chans;
			data = chans.collect { |c|
				FloatArray.newFrom(Array.fill(frames, { |f| data[(f * chans) + c] }))
			};
			{
				var plot = this.new(data, buffer.sampleRate,
					title ?? { "buffer" + buffer.bufnum }, columns, width, height);
				action.value(plot);
			}.defer;
		});
	}

	/*
	Envelope of one channel: mins, maxs and RMS per column.

	Columns are never more numerous than samples, so short material gives fewer
	columns rather than empty ones.
	*/
	*envelope { |signal, columns = 900|
		var n = signal.size, mins, maxs, rms;
		if(n == 0) { ^(mins: [0], maxs: [0], rms: [0]) };
		columns = columns.clip(1, n);
		mins = Array.newClear(columns);
		maxs = Array.newClear(columns);
		rms = Array.newClear(columns);
		columns.do { |c|
			var lo = (c * n / columns).floor.asInteger;
			var hi = (((c + 1) * n / columns).floor.asInteger).max(lo + 1).min(n);
			var mn = inf, mx = inf.neg, sum = 0;
			(lo .. hi - 1).do { |i|
				var v = signal[i];
				mn = min(mn, v); mx = max(mx, v);
				sum = sum + (v * v);
			};
			mins[c] = mn; maxs[c] = mx;
			rms[c] = (sum / (hi - lo)).sqrt;
		};
		^(mins: mins, maxs: maxs, rms: rms)
	}

	/*
	The same, read from a soundfile in chunks.

	samplesPerColumn bounds the work: sclang would spend a long time visiting
	every sample of a long file, so past that limit it visits an evenly spread
	subset instead. The stride used is reported, never applied silently.

	Returns (channels:, duration:, sampleRate:, numChannels:, stride:) or nil.
	*/
	*envelopeFromSoundFile { |path, columns = 900, startTime = 0, endTime,
		samplesPerColumn = 2000|
		var sf, chans, frames, startFrame, endFrame, span, stride, chunkFrames;
		var mins, maxs, sums, counts, chunk, frame, out;

		sf = SoundFile.openRead(path.standardizePath);
		if(sf.isNil) { ("PltWave: cannot read" + path).warn; ^nil };

		chans = sf.numChannels;
		frames = sf.numFrames;
		startFrame = (startTime * sf.sampleRate).floor.asInteger.clip(0, frames - 1);
		endFrame = if(endTime.isNil) { frames } {
			(endTime * sf.sampleRate).ceil.asInteger.clip(startFrame + 1, frames)
		};
		span = endFrame - startFrame;
		columns = columns.clip(1, span);
		stride = (span / (columns * samplesPerColumn)).ceil.asInteger.max(1);

		mins = chans.collect { inf ! columns };
		maxs = chans.collect { inf.neg ! columns };
		sums = chans.collect { 0.0 ! columns };
		counts = chans.collect { 0 ! columns };

		chunkFrames = 8192;
		chunk = FloatArray.newClear(chunkFrames * chans);
		sf.seek(startFrame, 0);
		frame = startFrame;

		block { |break|
			while({ sf.readData(chunk); chunk.size > 0 }, {
				var got = chunk.size div: chans;
				forBy(0, got - 1, stride) { |f|
					var col = ((frame + f - startFrame) * columns / span).floor.asInteger
						.clip(0, columns - 1);
					chans.do { |ch|
						var v = chunk[(f * chans) + ch];
						if(v < mins[ch][col]) { mins[ch][col] = v };
						if(v > maxs[ch][col]) { maxs[ch][col] = v };
						sums[ch][col] = sums[ch][col] + (v * v);
						counts[ch][col] = counts[ch][col] + 1;
					};
				};
				frame = frame + got;
				if(frame >= endFrame) { break.value };
				chunk = FloatArray.newClear(chunkFrames * chans);
			});
		};

		// a column no sample landed in would otherwise draw a line to infinity
		out = chans.collect { |ch|
			columns.do { |c|
				if(counts[ch][c] == 0) {
					mins[ch][c] = 0; maxs[ch][c] = 0; counts[ch][c] = 1;
				};
			};
			(
				mins: mins[ch], maxs: maxs[ch],
				rms: columns.collect { |c| (sums[ch][c] / counts[ch][c]).sqrt }
			)
		};

		out = (
			channels: out,
			duration: span / sf.sampleRate,
			sampleRate: sf.sampleRate,
			numChannels: chans,
			stride: stride,
			startTime: startFrame / sf.sampleRate
		);
		sf.close;
		^out
	}

	initPltWave { |data, argSampleRate, columns|
		var list = if(data.isNil) { [[0]] } {
			if(data.first.isNumber) { [data] } { data.asArray }
		};
		sampleRate = argSampleRate;
		numChannels = list.size;
		duration = list.first.size / sampleRate;
		channels = list.collect { |ch| this.class.envelope(ch, columns) };
		source = (data: list);
		xLabel = "seconds"; yLabel = "amp";
		this.refresh;
		^this
	}

	prSetEnvelope { |env|
		channels = env[\channels];
		duration = env[\duration];
		sampleRate = env[\sampleRate];
		numChannels = env[\numChannels];
		stride = env[\stride] ? 1;
		xLabel = "seconds"; yLabel = if(dbScale) { "dB" } { "amp" };
		this.refresh;
		^this
	}

	prSetSource { |argSource| source = argSource; ^this }

	// Recompute over a time range. Needs a soundfile source: data given directly
	// is already reduced to columns, and cannot be looked into more closely.
	zoom { |startTime = 0, endTime, columns = 900|
		var env;
		if(source[\path].isNil) {
			"PltWave: zoom needs a soundfile source".warn;
			^this
		};
		env = this.class.envelopeFromSoundFile(source[\path], columns, startTime, endTime);
		if(env.notNil) { this.prSetEnvelope(env) };
		^this
	}

	dbScale_ { |bool|
		dbScale = bool;
		yLabel = if(bool) { "dB" } { "amp" };
		this.refresh;
		^this
	}

	// Peak of the whole plot, in dB.
	peakDb {
		^PlotLib.ampDb(channels.collect { |ch|
			max(ch[\maxs].maxItem.abs, ch[\mins].minItem.abs)
		}.maxItem, floorDb)
	}

	dataBounds {
		^if(dbScale) { [0, duration, floorDb, 0] } { [0, duration, -1, 1] }
	}

	caption {
		var line = duration.round(0.001).asString ++ " s," + sampleRate.asInteger ++ " Hz,"
			+ numChannels ++ if(numChannels == 1) { " ch," } { " ch," }
			+ "peak" + this.peakDb.round(0.1) ++ " dB";
		^if(stride > 1) { line ++ ", stride" + stride } { line }
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var b = this.dataBounds;
		var h = (r.height - (panelGap * (numChannels - 1))) / numChannels;
		numChannels.do { |i|
			var panel = Rect(r.left, r.top + (i * (h + panelGap)), r.width, h);
			var last = i == (numChannels - 1);
			this.drawPanel(panel, b, { this.prDrawChannel(channels[i], panel, b) },
				last, true);
		};
		this.prCaption(v, r);
	}

	prDrawChannel { |ch, r, b|
		var mins = ch[\mins], maxs = ch[\maxs], rms = ch[\rms];
		var n = mins.size;
		var xOf = { |c| r.left + (c * r.width / n) };

		if(dbScale) {
			// single sided: the peak magnitude of each column, in dB. Kept light,
			// because what this view is for is the distance from peak to RMS.
			Pen.fillColor = PlotLib.color(traceColorKey).copy
				.alpha_(PlotLib.alphaFor(0.45));
			n.do { |c|
				var peak = max(maxs[c].abs, mins[c].abs);
				var y = this.yPix(PlotLib.ampDb(peak, floorDb), r, b);
				Pen.addRect(Rect(xOf.value(c), y, (r.width / n).max(1), r.bottom - y));
			};
			Pen.fill;
			if(showRms) {
				Pen.strokeColor = PlotLib.color(rmsColorKey);
				Pen.width = PlotLib.lineWidth(1);
				n.do { |c|
					var p = Point(xOf.value(c),
						this.yPix(PlotLib.ampDb(rms[c], floorDb), r, b));
					if(c == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
				};
				Pen.stroke;
			};
			^this
		};

		if(showRms) {
			// the RMS band sits inside the peak envelope, so draw it first
			Pen.fillColor = PlotLib.color(rmsColorKey).copy
				.alpha_(PlotLib.alphaFor(0.55));
			n.do { |c|
				var top = this.yPix(rms[c], r, b), bot = this.yPix(rms[c].neg, r, b);
				Pen.addRect(Rect(xOf.value(c), top, (r.width / n).max(1),
					(bot - top).max(1)));
			};
			Pen.fill;
		};

		Pen.fillColor = PlotLib.color(traceColorKey).copy.alpha_(PlotLib.alphaFor(0.9));
		n.do { |c|
			var top = this.yPix(maxs[c], r, b), bot = this.yPix(mins[c], r, b);
			Pen.addRect(Rect(xOf.value(c), top, (r.width / n).max(1), (bot - top).max(1)));
		};
		Pen.fill;
	}
}
