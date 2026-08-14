/*
Scrolling spectrogram: time across, frequency up, magnitude as brightness.

	x = PltSpectrogram(0).front;          // the left output channel
	x.logFreq_(true);
	x.close;

	PltSpectrogram.fromSoundFile(path).front;    // a whole file, offline

SuperCollider has FreqScope, which is one slice moving in time. This keeps the
history, which is where the structure of a sound actually shows.

Each frame writes ONE column into an Image and the Image is blitted, rather than
a UserView redrawing a few hundred rects per row per frame. The column ring
buffers: writeCol advances and wraps, and drawing takes two pieces so the newest
column is always at the right edge.
*/
PltSpectrogram : PltView {
	var <bus, <fftSize, <server;
	var <mags;                     // the most recent frame
	var image, imageCols, imageRows, writeCol = 0, filled = 0, ramp;
	var pending;                   // offline frames, written when the image exists
	var offlineSampleRate;         // set when the frames came from a file
	var buffer, synth, poller, restartFunc;
	var <>minHz = 40, <>maxHz = 12000, <>floorDb = -78, <>ceilDb = 0;
	var <logFreq = false, <>pollRate = 25, <>span = 8;
	// brightness gamma: mapped linearly, a noise floor 30 dB up from the floor
	// already reads as half ink and the whole picture washes out
	var <>gamma = 2.4;

	*new { |bus = 0, fftSize = 1024, server, title = "spectrogram",
		width = 900, height = 420|
		^super.new(title, width, height)
			.initPltSpectrogram(bus, fftSize, server ? Server.default)
	}

	/*
	A whole soundfile, analysed offline. Runs an NRT-free path: the file is read
	in the language and transformed with the FFT in Signal, so no server is
	needed and the result is the same on every run.
	*/
	*fromSoundFile { |path, fftSize = 1024, hop = 0.25, title,
		width = 900, height = 420|
		var plot = super.new(title ?? { PathName(path).fileName }, width, height)
			.initPltSpectrogram(0, fftSize, nil);
		var frames = this.framesFromSoundFile(path, fftSize, hop);
		if(frames.isNil) { ^plot };
		plot.prSetFrames(frames);
		^plot
	}

	/*
	Magnitude frames of a soundfile: an Array of frames, each fftSize/2 + 1 long.

	Signal:fft wants a real and an imaginary Signal and a cosine table; the
	magnitudes come out as the hypotenuse of the two halves.
	*/
	*framesFromSoundFile { |path, fftSize = 1024, hop = 0.25|
		var sf = SoundFile.openRead(path.standardizePath);
		var data, chans, frames, step, out, cosTable, window, half;
		if(sf.isNil) { ("PltSpectrogram: cannot read" + path).warn; ^nil };
		chans = sf.numChannels;
		data = FloatArray.newClear(sf.numFrames * chans);
		sf.readData(data);
		sf.close;
		// mix to mono: a spectrogram of one channel of a stereo file misleads
		if(chans > 1) {
			data = FloatArray.newFrom(
				Array.fill(data.size div: chans, { |f|
					var sum = 0;
					chans.do { |c| sum = sum + data[(f * chans) + c] };
					sum / chans
				})
			);
		};
		step = (fftSize * hop).asInteger.max(1);
		half = (fftSize div: 2);
		cosTable = Signal.fftCosTable(fftSize);
		window = Signal.hanningWindow(fftSize);
		out = Array.new((data.size div: step) + 1);
		forBy(0, data.size - fftSize - 1, step) { |start|
			var real = Signal.newClear(fftSize), imag = Signal.newClear(fftSize), c;
			fftSize.do { |i| real[i] = data[start + i] * window[i] };
			c = fft(real, imag, cosTable);
			out = out.add(
				Array.fill(half + 1, { |i|
					// scaled like the live path, so both read 0 dBFS at 0 dB
					hypot(c.real[i], c.imag[i]) * 4 / fftSize
				})
			);
		};
		^(frames: out, duration: (data.size / sf.sampleRate), sampleRate: sf.sampleRate)
	}

	initPltSpectrogram { |argBus, argSize, argServer|
		bus = argBus;
		fftSize = argSize;
		server = argServer;
		mags = 0 ! ((fftSize div: 2) + 1);
		xLabel = "seconds"; yLabel = "Hz";
		padRight = 22;
		if(server.notNil) {
			restartFunc = { this.prRestart };
			ServerTree.add(restartFunc, server);
			if(server.serverRunning) { this.start };
		};
		^this
	}

	// ---------------- live analysis ----------------

	start {
		if(poller.notNil or: { server.isNil }) { ^this };
		poller = Routine({
			var wait = 1 / pollRate.max(1);
			buffer = Buffer.alloc(server, fftSize, 1);
			server.sync;
			synth = {
				FFT(buffer.bufnum, In.ar(bus, 1), 0.5, 1);
				DC.ar(0)
			}.play(RootNode(server), addAction: \addToTail);
			server.sync;
			loop {
				buffer.getToFloatArray(wait: 0.01, timeout: 2, action: { |data|
					if(data.notNil and: { poller.notNil }) {
						this.pushFrame(PltSpectrum.magnitudes(data) * (4 / fftSize));
					};
				});
				wait.wait;
			}
		}).play(AppClock);
		^this
	}

	stop {
		var b = buffer;
		poller.stop; poller = nil;
		synth.free; synth = nil;
		buffer = nil;
		if(b.notNil) { AppClock.sched(0.3, { b.free; nil }) };
		^this
	}

	prRestart {
		poller.stop; poller = nil;
		synth = nil;
		buffer.free; buffer = nil;
		this.start;
		^this
	}

	free {
		if(restartFunc.notNil) { ServerTree.remove(restartFunc, server) };
		this.stop;
		image.free; image = nil;
		^this
	}

	// ---------------- the image ----------------

	logFreq_ { |bool|
		logFreq = bool;
		this.prRebuild;      // the row mapping changed, so the history is wrong
		^this
	}

	// Rebuild the image and forget the history, after a change of axis or size.
	prRebuild {
		image.free;
		image = nil;
		writeCol = 0;
		filled = 0;
		this.refresh;
		^this
	}

	prEnsureImage { |r|
		var cols = (span * pollRate).round.asInteger.max(2);
		var rows = r.height.round.asInteger.max(2);
		if(image.notNil and: { imageCols == cols } and: { imageRows == rows }) { ^image };
		image.free;
		imageCols = cols;
		imageRows = rows;
		image = Image.newEmpty(imageCols, imageRows);
		image.fill(PlotLib.color(\bg));
		writeCol = 0;
		filled = 0;
		^image
	}

	// dB to colour: the theme's ground, ink and trace, so it matches the rest.
	prRamp {
		var steps = 128;
		if(ramp.notNil) { ^ramp };
		ramp = Int32Array.newFrom(Array.fill(steps, { |i|
			var t = i / (steps - 1);
			var col = if(t < 0.5) {
				PlotLib.color(\bg).blend(PlotLib.color(\ink), t * 2)
			} {
				PlotLib.color(\ink).blend(PlotLib.color(\trace), (t - 0.5) * 2)
			};
			Image.colorToPixel(col)
		}));
		^ramp
	}

	applyTheme { ramp = nil; this.prRebuild; ^super.applyTheme }

	// One frame of magnitudes, written as one column.
	pushFrame { |frame|
		mags = frame;
		if(image.notNil) { this.prWriteColumn(frame) };
		this.refresh;
		^this
	}

	// One column of magnitudes as pixels, ready for setPixels.
	prColumnPixels { |frame|
		var col = this.class.column(frame, imageRows, minHz, maxHz, floorDb, ceilDb,
			logFreq, this.sampleRate, fftSize);
		var lut = this.prRamp, n = lut.size;
		^Int32Array.newFrom(col.collect { |v|
			lut[((v ** gamma) * (n - 1)).round.asInteger.clip(0, n - 1)]
		})
	}

	/*
	One column of the picture, top row first, as values from 0 to 1.

	Each row takes the loudest bin that falls in it, so a partial that shares a
	row with silence still shows. Pure, so the mapping can be tested without a
	window.
	*/
	*column { |frame, rows, minHz = 40, maxHz = 12000, floorDb = -78, ceilDb = 0,
		logFreq = false, sampleRate = 48000, fftSize = 1024|
		var nyquist = sampleRate / 2;
		var bins = frame.size;
		var out = Array.newClear(rows);
		rows.do { |row|
			// row 0 is the top of the picture, which is the high end
			var t0 = (rows - 1 - row) / rows, t1 = (rows - row) / rows;
			var fLo, fHi, iLo, iHi, m = 0, db;
			if(logFreq) {
				fLo = 10 ** PlotLib.map(t0, 0, 1, minHz.max(1).log10, maxHz.log10);
				fHi = 10 ** PlotLib.map(t1, 0, 1, minHz.max(1).log10, maxHz.log10);
			} {
				fLo = PlotLib.map(t0, 0, 1, 0, maxHz);
				fHi = PlotLib.map(t1, 0, 1, 0, maxHz);
			};
			iLo = (fLo / nyquist * (bins - 1)).floor.asInteger.clip(0, bins - 1);
			iHi = (fHi / nyquist * (bins - 1)).ceil.asInteger.clip(iLo, bins - 1);
			if(iHi > (iLo + 1)) {
				// several bins in this row: take the loudest, so a narrow partial
				// sharing a row with silence still shows
				(iLo .. iHi).do { |i| m = max(m, frame[i]) };
			} {
				/*
				The row is narrower than a bin, which is the case across the whole
				low end of a log axis: at 2048 points there are nine bins below
				200 Hz. Repeating the bin draws them as horizontal bands, so
				interpolate between neighbours at the row's centre instead.
				*/
				var centre = (fLo + fHi) * 0.5;
				var pos = (centre / nyquist * (bins - 1)).clip(0, bins - 1);
				var i0 = pos.floor.asInteger;
				var i1 = (i0 + 1).min(bins - 1);
				m = frame[i0] + ((frame[i1] - frame[i0]) * (pos - i0));
			};
			db = PlotLib.ampDb(m, floorDb);
			out[row] = ((db - floorDb) / (ceilDb - floorDb)).clip(0, 1);
		};
		^out
	}

	// ---------------- offline frames ----------------

	prSetFrames { |analysis|
		var frames = analysis[\frames];
		span = analysis[\duration];
		pollRate = frames.size / span.max(1e-9);
		offlineSampleRate = analysis[\sampleRate];
		// pending BEFORE prRebuild: refresh defers, and a defer from the AppClock
		// runs immediately, so a rebuild first would draw with nothing pending and
		// never come back
		pending = frames;
		this.prRebuild;
		^this
	}

	// the file's rate offline, the server's when live: getting this wrong puts
	// every partial on the wrong row
	sampleRate {
		^offlineSampleRate ?? { if(server.notNil) { server.sampleRate } ? 48000 }
	}

	dataBounds {
		^if(logFreq) {
			[span.neg, 0, minHz.max(1).log10, maxHz.log10]
		} {
			[span.neg, 0, 0, maxHz]
		}
	}

	xTickValues { |b| ^PlotLib.ticks(b[0], b[1], xTicks) }

	yTickValues { |b|
		if(logFreq.not) { ^super.yTickValues(b) };
		^[50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]
			.select { |f| f >= minHz and: { f <= maxHz } }
			.collect(_.log10)
	}

	yTickLabel { |value, b|
		var hz;
		if(logFreq.not) { ^super.yTickLabel(value, b) };
		hz = (10 ** value).round(1).asInteger;
		^if(hz >= 1000) { (hz / 1000).asInteger.asString ++ "k" } { hz.asString }
	}

	caption {
		^fftSize.asString ++ "-point FFT," + span.round(0.1) ++ "s window"
	}

	drawData { |v, r, b|
		var img, w = r.width;
		this.prEnsureImage(r);
		// read the image AFTER this: writing pending frames replaces it, and a
		// local captured earlier would be blitting a freed image, silently
		if(pending.notNil) { this.prDrawPending };
		img = image;
		if((filled == 0) or: { img.isNil }) { ^this };
		// two pieces, so the newest column lands at the right edge
		if(filled < imageCols) {
			img.drawInRect(
				Rect(r.right - (filled * w / imageCols), r.top,
					filled * w / imageCols, r.height),
				Rect(0, 0, filled, imageRows));
			^this
		};
		img.drawInRect(
			Rect(r.left, r.top, (imageCols - writeCol) * w / imageCols, r.height),
			Rect(writeCol, 0, imageCols - writeCol, imageRows));
		if(writeCol > 0) {
			img.drawInRect(
				Rect(r.left + ((imageCols - writeCol) * w / imageCols), r.top,
					writeCol * w / imageCols, r.height),
				Rect(0, 0, writeCol, imageRows));
		};
	}

	// Offline frames are written once, when the image first exists.
	prDrawPending {
		var frames = pending;
		pending = nil;
		imageCols = frames.size.max(2);
		image.free;
		image = Image.newEmpty(imageCols, imageRows);
		image.fill(PlotLib.color(\bg));
		writeCol = 0; filled = 0;
		frames.do { |f| this.prWriteColumn(f) };
	}

	prWriteColumn { |frame|
		image.setPixels(this.prColumnPixels(frame), Rect(writeCol, 0, 1, imageRows));
		writeCol = (writeCol + 1) % imageCols;
		filled = (filled + 1).min(imageCols);
	}
}
