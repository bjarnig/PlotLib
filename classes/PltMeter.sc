/*
Live level meter: one vertical bar per channel, RMS filled, peak as a line,
peak held for a moment, clipping marked at the top.

	m = PltMeter(2, 0).front;      // the first two output channels
	m.close;                       // frees the synth and the responder

The analysis runs on the server (SendPeakRMS at the tail of the root node) and
arrives as OSC. pushFrame does the same job from the language, so the view can
be driven from anything, including a test.
*/
PltMeter : PltView {
	classvar nextId = 0;

	var <numChannels, <bus, <server, <replyId;
	var <peaks, <rmss, <holds, <holdStamps;
	var synth, responder, restartFunc;
	// peakLag is SendPeakRMS's own decay. It defaults to 3 seconds there, which
	// makes the peak line fall slowly and duplicate the hold line; keep it short
	// and let holdTime do the holding.
	var <>floorDb = -60, <>holdTime = 1.5, <>replyRate = 20, <>peakLag = 0.1;
	var rmsColor, peakColor, clipColor;
	var <>rmsColorKey = \ink, <>peakColorKey = \trace, <>clipColorKey = \rose;

	*new { |numChannels = 2, bus = 0, server, title = "meter", width = 300, height = 320|
		^super.new(title, width, height)
			.initPltMeter(numChannels, bus, server ? Server.default)
	}

	initPltMeter { |argChannels, argBus, argServer|
		numChannels = argChannels.max(1);
		bus = argBus;
		server = argServer;
		replyId = nextId;
		nextId = nextId + 1;
		peaks = 0 ! numChannels;
		rmss = 0 ! numChannels;
		holds = 0 ! numChannels;
		holdStamps = 0 ! numChannels;
		// the bottom pad holds the channel numbers and the caption line under them
		padLeft = 46; padBottom = 42; padRight = 14;
		yLabel = "dB";
		// cmd-period frees the analysis synth and ServerTree fires straight after.
		// Without re-registering there, the view keeps showing the last levels it
		// received, for ever, which is worse than showing nothing.
		restartFunc = { this.prRestart };
		ServerTree.add(restartFunc, server);
		if(server.serverRunning) { this.start };
	}

	start {
		if(synth.notNil) { ^this };
		responder = OSCFunc({ |msg|
			// [cmd, nodeID, replyID, peak0, rms0, peak1, rms1, ...]
			if(msg[2] == replyId) {
				var vals = msg[3..];
				this.pushFrame(
					numChannels.collect { |i| vals[i * 2] ? 0 },
					numChannels.collect { |i| vals[(i * 2) + 1] ? 0 }
				);
			}
		}, '/plotlib_meter', server.addr);

		synth = {
			// at the tail of the root node, so In.ar sees what everything else wrote
			SendPeakRMS.kr(In.ar(bus, numChannels), replyRate, peakLag,
				'/plotlib_meter', replyId);
			DC.ar(0)    // the synth needs an output; this adds nothing to the bus
		}.play(RootNode(server), addAction: \addToTail);
		^this
	}

	stop {
		synth.free; synth = nil;
		responder.free; responder = nil;
		^this
	}

	// The nodes are already gone at this point, so drop the stale references
	// rather than sending /n_free to nothing.
	prRestart {
		synth = nil;
		responder.free; responder = nil;
		this.start;
		^this
	}

	free {
		ServerTree.remove(restartFunc, server);
		this.stop;
		^this
	}

	// One frame of levels, as linear amplitudes.
	pushFrame { |argPeaks, argRms|
		var now = Main.elapsedTime;
		numChannels.do { |i|
			peaks[i] = (argPeaks[i] ? 0).abs;
			rmss[i] = (argRms[i] ? 0).abs;
			if(peaks[i] >= holds[i] or: { (now - holdStamps[i]) > holdTime }) {
				holds[i] = peaks[i];
				holdStamps[i] = now;
			};
		};
		this.refresh;
		^this
	}

	rmsColor { ^rmsColor ?? { PlotLib.color(rmsColorKey) } }
	rmsColor_ { |c| rmsColor = c; ^this }
	peakColor { ^peakColor ?? { PlotLib.color(peakColorKey) } }
	peakColor_ { |c| peakColor = c; ^this }
	clipColor { ^clipColor ?? { PlotLib.color(clipColorKey) } }
	clipColor_ { |c| clipColor = c; ^this }

	// True while any channel has hit full scale within the hold time.
	clipping { ^holds.any { |h| h >= 1.0 } }

	dataBounds { ^[0, numChannels, floorDb, 0] }

	// the loudest channel only: a per-channel list runs off the side of a narrow
	// meter window, and the numbers are already in the bars
	caption { ^"peak" + PlotLib.ampDb(peaks.maxItem, floorDb).round(0.1) ++ " dB" }

	prDraw { |v|
		var r = this.plotRect(v);
		// dB, not the channel index, is what the y ticks mean; x is labelled below
		this.drawPanel(r, this.dataBounds, { this.drawData(v, r, this.dataBounds) },
			false, true);
		numChannels.do { |i|
			var slot = this.prSlot(r, i);
			Pen.stringCenteredIn(( i + 1 ).asString,
				Rect(slot.left, r.bottom + 5, slot.width, 12),
				PlotLib.font(9), PlotLib.color(\muted));
		};
		this.prCaption(v, r);
	}

	prSlot { |r, i|
		var w = r.width / numChannels;
		// proportional padding, bounded at both ends: a fixed cap makes a two
		// channel meter look like a bar chart and an eight channel one look solid
		var pad = (w * 0.25).clip(3, 30);
		^Rect(r.left + (i * w) + pad, r.top, w - (pad * 2), r.height)
	}

	drawData { |v, r, b|
		numChannels.do { |i|
			var slot = this.prSlot(r, i);
			var rmsY = this.yPix(PlotLib.ampDb(rmss[i], floorDb), r, b);
			var peakY = this.yPix(PlotLib.ampDb(peaks[i], floorDb), r, b);
			var holdY = this.yPix(PlotLib.ampDb(holds[i], floorDb), r, b);

			Pen.fillColor = this.rmsColor.copy.alpha_(PlotLib.alphaFor(0.8));
			Pen.fillRect(Rect(slot.left, rmsY, slot.width, r.bottom - rmsY));

			Pen.strokeColor = if(peaks[i] >= 1.0) { this.clipColor } { this.peakColor };
			Pen.width = PlotLib.lineWidth(1.5);
			Pen.line(Point(slot.left, peakY), Point(slot.right, peakY));
			Pen.stroke;

			Pen.strokeColor = if(holds[i] >= 1.0) { this.clipColor } {
				this.peakColor.copy.alpha_(PlotLib.alphaFor(0.45))
			};
			Pen.width = PlotLib.lineWidth(1);
			Pen.line(Point(slot.left, holdY + 0.5), Point(slot.right, holdY + 0.5));
			Pen.stroke;

			if(holds[i] >= 1.0) {
				Pen.fillColor = this.clipColor;
				Pen.fillRect(Rect(slot.left, r.top - 6, slot.width, 4));
			};
		};
	}
}
