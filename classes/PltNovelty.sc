/*
Spectral novelty: how much the spectrum changed from one frame to the next, with
the detection threshold drawn across it and the onsets it picks as ticks.

This is the curve an onset detector actually looks at, so a missed hit or a double
trigger stops being mysterious. Offline from a file, or live from a bus alongside
the server's own Onsets for comparison.
*/
PltNovelty : PltView {
	var <flux, <onsets, <duration, <frameRate, <sampleRate;
	var <bus, <server, <fftSize;
	var buffer, synth, poller, onsetResponder, restartFunc, replyId, previous;
	var live, liveFlux, serverOnsets, runningMax = 1e-9;
	// threshold has its own setter below, so it is declared getter-only here
	var <threshold = 0.25, <>minGap = 0.05, <>span = 8, <>pollRate = 25;
	var <>fluxColorKey = \ink, <>onsetColorKey = \gold, <>serverColorKey = \rose;

	classvar nextId = 0;

	// A whole file, analysed offline: the same frames PltSpectrogram computes.
	*fromSoundFile { |path, fftSize = 1024, hop = 0.25, threshold = 0.25,
		title, width = 900, height = 400|
		var analysis = PltSpectrogram.framesFromSoundFile(path, fftSize, hop);
		if(analysis.isNil) { ^nil };
		^super.new(title ?? { PathName(path).fileName }, width, height)
			.initPltNovelty(fftSize)
			.prSetFrames(analysis, hop, threshold)
	}

	// Live from a bus. The server's own Onsets is drawn too, in a second colour,
	// so its decisions can be compared with the curve.
	*live { |bus = 0, fftSize = 1024, server, threshold = 0.25, title = "novelty",
		width = 900, height = 400|
		^super.new(title, width, height)
			.initPltNovelty(fftSize)
			.prSetLive(bus, server ? Server.default, threshold)
	}

	/*
	Novelty per frame: the sum of increases in magnitude, normalised to its own
	maximum so the threshold reads 0 to 1.

	Only increases count. A spectrum falling away is not an onset, and counting the
	full difference makes every note ending look like a note starting.
	*/
	*flux { |frames|
		var out = Array.newClear(frames.size);
		var peak = 0;
		frames.do { |frame, i|
			var sum = 0;
			if(i > 0) {
				var prev = frames[i - 1];
				frame.size.min(prev.size).do { |k|
					var d = frame[k] - prev[k];
					if(d > 0) { sum = sum + d };
				};
			};
			out[i] = sum;
			peak = max(peak, sum);
		};
		if(peak <= 0) { ^out };
		^out.collect { |v| v / peak }
	}

	/*
	Frames where the novelty is above the threshold and higher than its neighbours,
	with anything inside minGapFrames of the last pick suppressed.

	A plain threshold crossing fires several times on one attack, which is what a
	doubled onset is.
	*/
	*peaks { |values, threshold = 0.25, minGapFrames = 3|
		var out = Array.new, last = minGapFrames.neg - 1;
		values.do { |v, i|
			var before = values[i - 1] ? 0, after = values[i + 1] ? 0;
			if((v >= threshold) and: { v >= before } and: { v >= after }
				and: { (i - last) > minGapFrames }) {
				out = out.add(i);
				last = i;
			};
		};
		^out
	}

	initPltNovelty { |argFftSize|
		fftSize = argFftSize;
		flux = [];
		onsets = [];
		liveFlux = List.new;
		serverOnsets = List.new;
		xLabel = "seconds"; yLabel = "novelty";
		^this
	}

	prSetFrames { |analysis, hop, argThreshold|
		var frames = analysis[\frames];
		live = false;
		threshold = argThreshold;
		duration = analysis[\duration];
		sampleRate = analysis[\sampleRate];
		frameRate = frames.size / duration.max(1e-9);
		flux = this.class.flux(frames);
		this.prPickOnsets;
		this.refresh;
		^this
	}

	prPickOnsets {
		onsets = this.class.peaks(flux, threshold, (minGap * frameRate).round.max(1));
		^this
	}

	threshold_ { |value|
		threshold = value;
		if(live.not) { this.prPickOnsets };
		this.refresh;
		^this
	}

	/*
	The onsets in seconds. Offset to the middle of the analysis window: a frame is
	named by where it starts, and a 1024 point window at 48 kHz starts 11 ms before
	the middle of what it heard, which reported every click that much too early.
	*/
	onsetTimes {
		var offset = if(sampleRate.isNil) { 0 } { fftSize / 2 / sampleRate };
		^onsets.collect { |i| (i / frameRate.max(1e-9)) + offset }
	}

	// ---------------- live ----------------

	prSetLive { |argBus, argServer, argThreshold|
		live = true;
		bus = argBus;
		server = argServer;
		threshold = argThreshold;
		frameRate = pollRate;
		replyId = nextId;
		nextId = nextId + 1;
		restartFunc = { this.prRestart };
		ServerTree.add(restartFunc, server);
		if(server.serverRunning) { this.start };
		^this
	}

	start {
		if(poller.notNil or: { server.isNil }) { ^this };
		onsetResponder = OSCFunc({ |msg|
			if(msg[2] == replyId) { serverOnsets.add(Main.elapsedTime) };
		}, '/plt_novelty_onset', server.addr);

		poller = Routine({
			var wait = 1 / pollRate.max(1);
			buffer = Buffer.alloc(server, fftSize, 1);
			server.sync;
			synth = {
				var in = In.ar(bus, 1);
				FFT(buffer.bufnum, in, 0.5, 1);
				// Onsets gets its own chain: sharing one stops it triggering at all
				SendReply.kr(Onsets.kr(FFT(LocalBuf(512), in), 0.5),
					'/plt_novelty_onset', [1], replyId);
				DC.ar(0)
			}.play(RootNode(server), addAction: \addToTail);
			server.sync;
			loop {
				buffer.getToFloatArray(wait: 0.01, timeout: 2, action: { |data|
					if(data.notNil and: { poller.notNil }) {
						this.pushFrame(PltSpectrum.magnitudes(data));
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
		onsetResponder.free; onsetResponder = nil;
		buffer = nil;
		if(b.notNil) { AppClock.sched(0.3, { b.free; nil }) };
		^this
	}

	prRestart {
		poller.stop; poller = nil;
		synth = nil;
		onsetResponder.free; onsetResponder = nil;
		buffer.free; buffer = nil;
		this.start;
		^this
	}

	free {
		if(restartFunc.notNil) { ServerTree.remove(restartFunc, server) };
		this.stop;
		^this
	}

	/*
	One frame of magnitudes. The live novelty is normalised against a running
	maximum that decays, so a loud passage does not flatten everything after it.
	*/
	pushFrame { |frame|
		var sum = 0, now = Main.elapsedTime;
		if(previous.notNil) {
			frame.size.min(previous.size).do { |k|
				var d = frame[k] - previous[k];
				if(d > 0) { sum = sum + d };
			};
		};
		previous = frame.copy;
		runningMax = max(sum, runningMax * 0.999);
		liveFlux.add([now, sum / runningMax.max(1e-9)]);
		if(liveFlux.size > 4000) { liveFlux.removeAt(0) };
		this.refresh;
		^this
	}

	reset { liveFlux.clear; serverOnsets.clear; runningMax = 1e-9; ^this }

	dataBounds { ^if(live) { [span.neg, 0, 0, 1] } { [0, duration ? 1, 0, 1] } }

	caption {
		if(live) { ^liveFlux.size.asString + "frames, threshold" + threshold };
		^onsets.size.asString + "onsets at threshold" + threshold ++ ","
			+ frameRate.round(0.1) + "frames per second"
	}

	drawData { |v, r, b|
		var now = Main.elapsedTime;

		Pen.strokeColor = PlotLib.color(\muted).copy.alpha_(PlotLib.alphaFor(0.7));
		Pen.width = PlotLib.lineWidth(1);
		Pen.line(Point(r.left, this.yPix(threshold, r, b)),
			Point(r.right, this.yPix(threshold, r, b)));
		Pen.stroke;

		if(live) {
			while({ liveFlux.size > 0 and: { liveFlux[0][0] < (now - span) } },
				{ liveFlux.removeAt(0) });
			while({ serverOnsets.size > 0 and: { serverOnsets[0] < (now - span) } },
				{ serverOnsets.removeAt(0) });

			// the server's decisions along the bottom, mine along the top, so the
			// two can be told apart at a glance
			Pen.strokeColor = PlotLib.color(serverColorKey).copy
				.alpha_(PlotLib.alphaFor(0.8));
			Pen.width = PlotLib.lineWidth(1.5);
			serverOnsets.do { |t|
				var px = this.xPix(t - now, r, b);
				Pen.line(Point(px, r.bottom), Point(px, r.bottom - (r.height * 0.14)));
			};
			Pen.stroke;

			Pen.strokeColor = PlotLib.color(fluxColorKey);
			Pen.width = PlotLib.lineWidth(1.2);
			liveFlux.do { |pair, i|
				var p = Point(this.xPix(pair[0] - now, r, b),
					this.yPix(pair[1].clip(0, 1), r, b));
				if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
			^this
		};

		// marks along the top rather than full height lines, which buried the curve
		// they are there to explain
		Pen.strokeColor = PlotLib.color(onsetColorKey).copy.alpha_(PlotLib.alphaFor(0.9));
		Pen.width = PlotLib.lineWidth(1.5);
		this.onsetTimes.do { |t|
			var px = this.xPix(t, r, b);
			Pen.line(Point(px, r.top), Point(px, r.top + (r.height * 0.14)));
		};
		Pen.stroke;

		Pen.fillColor = PlotLib.color(fluxColorKey).copy.alpha_(PlotLib.alphaFor(0.75));
		flux.do { |value, i|
			var px = this.xPix(i / frameRate.max(1e-9), r, b);
			var py = this.yPix(value.clip(0, 1), r, b);
			Pen.addRect(Rect(px, py, (r.width / flux.size.max(1)).max(1),
				(r.bottom - py).max(1)));
		};
		Pen.fill;
	}
}
