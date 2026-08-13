/*
Live spectrum analyser: magnitude against frequency, linear by default so
partials stay countable, logarithmic when the whole range matters.

	x = PltSpectrum(0).front;      // input bus 0, the left output channel
	x.maxHz_(4000);
	x.logFreq_(true);
	x.close;

An FFT UGen keeps writing frames into a buffer on the server and the language
polls that buffer. A poll can land mid-frame, which shows as a moment of noise
and is not worth locking against for a picture.
*/
PltSpectrum : PltView {
	var <bus, <fftSize, <server;
	var <mags;                     // magnitudes, bin 0 to fftSize/2
	var buffer, synth, poller, restartFunc;
	var holds;
	var <>maxHz = 12000, <>minHz = 20, <>floorDb = -84, <>ceilDb = 0, <>logFreq = false;
	var <>smooth = 0.5, <>showHold = true, <>holdDecayDb = 12;
	var traceColor, holdColor;
	var <>traceColorKey = \trace, <>holdColorKey = \ink, <>pollRate = 20;

	*new { |bus = 0, fftSize = 1024, server, title = "spectrum",
		width = 900, height = 420|
		^super.new(title, width, height)
			.initPltSpectrum(bus, fftSize, server ? Server.default)
	}

	initPltSpectrum { |argBus, argSize, argServer|
		bus = argBus;
		fftSize = argSize;
		server = argServer;
		mags = 0 ! ((fftSize / 2).asInteger + 1);
		holds = mags.copy;
		xLabel = "Hz"; yLabel = "dB";
		// cmd-period frees the synth and clears the clocks, so the poller dies too;
		// ServerTree fires afterwards and is where this gets rebuilt
		restartFunc = { this.prRestart };
		ServerTree.add(restartFunc, server);
		if(server.serverRunning) { this.start };
	}

	start {
		if(poller.notNil) { ^this };
		poller = Routine({
			var wait = 1 / pollRate.max(1);
			buffer = Buffer.alloc(server, fftSize, 1);
			// sync before the first read: polling a buffer whose /b_alloc has not
			// landed yet answers with "FAILURE IN SERVER /b_getn index out of range"
			server.sync;
			synth = {
				// no IFFT: the buffer is the output, holding the most recent frame
				FFT(buffer.bufnum, In.ar(bus, 1), 0.5, 1);
				DC.ar(0)
			}.play(RootNode(server), addAction: \addToTail);
			server.sync;
			loop {
				buffer.getToFloatArray(wait: 0.01, timeout: 2, action: { |data|
					// the buffer is freed on stop, so a read can still be in flight
					if(data.notNil and: { poller.notNil }) {
						this.pushFrame(this.class.magnitudes(data));
					};
				});
				wait.wait;
			}
		}).play(AppClock);
		^this
	}

	traceColor { ^traceColor ?? { PlotLib.color(traceColorKey) } }
	traceColor_ { |c| traceColor = c; ^this }
	holdColor { ^holdColor ?? { PlotLib.color(holdColorKey) } }
	holdColor_ { |c| holdColor = c; ^this }

	stop {
		var b = buffer;
		poller.stop; poller = nil;
		synth.free; synth = nil;
		buffer = nil;
		// a read may already be in flight; freeing the buffer under it makes the
		// server answer /b_getn with an index error
		if(b.notNil) { AppClock.sched(0.3, { b.free; nil }) };
		^this
	}

	// The synth is gone and the poller's clock has been cleared, but the buffer is
	// still allocated: free it here or every cmd-period leaks one.
	prRestart {
		poller.stop; poller = nil;
		synth = nil;
		buffer.free; buffer = nil;
		this.start;
		^this
	}

	free {
		ServerTree.remove(restartFunc, server);
		this.stop;
		^this
	}

	/*
	Magnitudes from the contents of an FFT buffer.

	scsynth stores a frame packed as [DC, nyquist, re1, im1, re2, im2, ...], so
	the two purely real bins come first and are not a complex pair. Returns
	fftSize/2 + 1 magnitudes, bin 0 to nyquist.
	*/
	*magnitudes { |data|
		var n = data.size;
		var half = (n / 2).asInteger;
		var out = Array.newClear(half + 1);
		out[0] = data[0].abs;
		out[half] = data[1].abs;
		(1 .. half - 1).do { |i|
			out[i] = hypot(data[i * 2], data[(i * 2) + 1]);
		};
		^out
	}

	// Bin centre frequency, for a given buffer size and sample rate.
	*binFreq { |bin, fftSize, sampleRate = 48000| ^bin * sampleRate / fftSize }

	/*
	One frame of magnitudes; smoothed in time, and holding the peaks.

	FFT output is unnormalised: a full scale sine under a Hann window peaks at
	about fftSize/4, so scale by its reciprocal to put 0 dBFS at 0 dB.
	*/
	pushFrame { |frame|
		var scale = 4 / fftSize;
		var decay = (holdDecayDb / pollRate.max(1)).neg.dbamp;   // dB per second
		frame.do { |m, i|
			if(i < mags.size) {
				mags[i] = (mags[i] * smooth) + (m * scale * (1 - smooth));
				holds[i] = max(mags[i], holds[i] * decay);
			}
		};
		this.refresh;
		^this
	}

	sampleRate { ^(if(server.notNil) { server.sampleRate } ? 48000) }

	dataBounds {
		^if(logFreq) {
			[minHz.max(1).log10, maxHz.log10, floorDb, ceilDb]
		} {
			[0, maxHz, floorDb, ceilDb]
		}
	}

	// In log mode the axis holds log10(Hz), where even ticks would read 1.5, 2.0,
	// 2.5. Label the frequencies a listener thinks in, and grid the same places.
	xTickValues { |b|
		if(logFreq.not) { ^super.xTickValues(b) };
		^[20, 30, 50, 100, 200, 300, 500, 1000, 2000, 3000, 5000, 10000, 20000]
			.select { |f| f >= minHz and: { f <= maxHz } }
			.collect(_.log10)
	}

	xTickLabel { |value, b|
		var hz;
		if(logFreq.not) { ^super.xTickLabel(value, b) };
		hz = (10 ** value).round(1).asInteger;
		^if(hz >= 1000) { (hz / 1000).asInteger.asString ++ "k" } { hz.asString }
	}

	caption {
		var top = mags.maxIndex;
		^"peak" + this.class.binFreq(top, fftSize, this.sampleRate).round(1).asInteger
			++ " Hz," + fftSize ++ "-point FFT"
	}

	drawData { |v, r, b|
		var sr = this.sampleRate;
		var nyquist = sr / 2;
		var cols = r.width.asInteger.max(1);
		var line = Array.new(cols), holdLine = Array.new(cols);

		// one column per pixel, taking the loudest bin that falls in it, so a
		// narrow peak survives however many bins share a column
		cols.do { |c|
			var fLo, fHi, iLo, iHi, m = 0, h = 0;
			if(logFreq) {
				fLo = 10 ** PlotLib.map(c, 0, cols, b[0], b[1]);
				fHi = 10 ** PlotLib.map(c + 1, 0, cols, b[0], b[1]);
			} {
				fLo = PlotLib.map(c, 0, cols, b[0], b[1]);
				fHi = PlotLib.map(c + 1, 0, cols, b[0], b[1]);
			};
			iLo = (fLo / nyquist * (mags.size - 1)).floor.asInteger.clip(0, mags.size - 1);
			iHi = (fHi / nyquist * (mags.size - 1)).ceil.asInteger.clip(iLo, mags.size - 1);
			(iLo .. iHi).do { |i|
				m = max(m, mags[i]);
				h = max(h, holds[i]);
			};
			line = line.add(m);
			holdLine = holdLine.add(h);
		};

		if(showHold) {
			Pen.strokeColor = this.holdColor.copy.alpha_(PlotLib.alphaFor(0.5));
			Pen.width = PlotLib.lineWidth(1);
			this.prTrace(holdLine, r, b);
			Pen.stroke;
		};

		Pen.strokeColor = this.traceColor;
		Pen.width = PlotLib.lineWidth(1.2);
		this.prTrace(line, r, b);
		Pen.stroke;
	}

	prTrace { |values, r, b|
		values.do { |m, c|
			var y = this.yPix(PlotLib.ampDb(m, floorDb), r, b);
			var p = Point(r.left + c, y);
			if(c == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
	}
}
