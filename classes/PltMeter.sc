/*
Live level meter: one bar per channel, RMS filled in three zones, peak as a line,
the peak held for a moment, clipping marked above the frame.

SendPeakRMS at the tail of the root node does the analysis; pushFrame does the same
job from the language, so the view can be driven from anything.
*/
PltMeter : PltView {
	classvar nextId = 0;

	var <numChannels, <bus, <server, <replyId;
	var <peaks, <rmss, <holds, <holdStamps;
	var synth, responder, restartFunc;
	// SendPeakRMS's own peak decay defaults to 3 s there, which duplicates the hold
	// line; kept short so holdTime does the holding.
	var <>floorDb = -60, <>holdTime = 1.5, <>replyRate = 20, <>peakLag = 0.1;
	var rmsColor, peakColor, clipColor;
	// Three zones in segments: green below warnDb, yellow to clipDb, red above.
	// Hardware thresholds, not full scale, which RMS never reaches.
	var <>safeColorKey = \mint, <>warnColorKey = \gold, <>clipColorKey = \rose;
	var <>peakColorKey = \trace, <>rmsColorKey;
	var <>warnDb = -12, <>clipDb = -3, <>zones = true;

	// One bar plus its gap per channel, so a stereo meter is a narrow window and a
	// sixteen channel one is a wide one, rather than both being 300 pixels.
	*barWidth { ^20 }
	*barGap { ^10 }

	// the constants are padLeft and padRight below, which hold the dB labels
	*widthFor { |numChannels = 2|
		^46 + 14 + (numChannels.max(1) * (this.barWidth + this.barGap))
	}

	*new { |numChannels = 2, bus = 0, server, title = "meter", width, height = 230|
		^super.new(title, width ?? { this.widthFor(numChannels) }, height)
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
		// cmd-period frees the synth; without this the view shows the last levels
		// it received for ever, which is worse than showing nothing.
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

	// Only the safe zone is lightened, being a large solid area; whitening the
	// gold turns it khaki and costs the zones their distinctness.
	prBarColor { |key|
		var base = PlotLib.color(key);
		if(key != safeColorKey) { ^base };
		^if(PlotLib.isLight) { base } { base.blend(Color.white, 0.22) }
	}

	// The single-colour bar, for zones_(false) or an explicit override.
	rmsColor { ^rmsColor ?? { this.prBarColor(rmsColorKey ? safeColorKey) } }
	rmsColor_ { |c| rmsColor = c; ^this }
	peakColor { ^peakColor ?? { PlotLib.color(peakColorKey) } }
	peakColor_ { |c| peakColor = c; ^this }
	clipColor { ^clipColor ?? { PlotLib.color(clipColorKey) } }
	clipColor_ { |c| clipColor = c; ^this }

	// Which zone a level in dB falls in: \safe, \warn or \clip.
	zoneOf { |db|
		if(db >= clipDb) { ^\clip };
		if(db >= warnDb) { ^\warn };
		^\safe
	}

	prZoneColor { |db|
		^switch(this.zoneOf(db),
			\clip, { this.clipColor },
			\warn, { this.prBarColor(warnColorKey) },
			{ this.prBarColor(safeColorKey) }
		)
	}

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
		// half a gap either side, so the bars sit close together and a resized
		// window keeps them proportional rather than letting them drift apart
		var pad = (w * 0.16).clip(2, this.class.barGap);
		^Rect(r.left + (i * w) + pad, r.top, (w - (pad * 2)).max(2), r.height)
	}

	drawData { |v, r, b|
		numChannels.do { |i|
			var slot = this.prSlot(r, i);
			var rmsDb = PlotLib.ampDb(rmss[i], floorDb);
			var rmsY = this.yPix(rmsDb, r, b);
			var peakDb = PlotLib.ampDb(peaks[i], floorDb);
			var peakY = this.yPix(peakDb, r, b);
			var holdY = this.yPix(PlotLib.ampDb(holds[i], floorDb), r, b);

			if(zones and: { rmsColor.isNil }) {
				// one segment per zone the bar passes through, bottom upward
				[[floorDb, warnDb, safeColorKey], [warnDb, clipDb, warnColorKey],
					[clipDb, 0, clipColorKey]].do { |seg|
					var lo = seg[0], hi = seg[1].min(rmsDb), top, bottom;
					if(rmsDb > lo) {
						top = this.yPix(hi, r, b);
						bottom = this.yPix(lo, r, b);
						Pen.fillColor = if(seg[2] == clipColorKey) { this.clipColor } {
							this.prBarColor(seg[2])
						}.copy.alpha_(PlotLib.alphaFor(0.8));
						Pen.fillRect(Rect(slot.left, top, slot.width,
							(bottom - top).max(1)));
					};
				};
			} {
				Pen.fillColor = this.rmsColor.copy.alpha_(PlotLib.alphaFor(0.8));
				Pen.fillRect(Rect(slot.left, rmsY, slot.width, r.bottom - rmsY));
			};

			Pen.strokeColor = if(zones) { this.prZoneColor(peakDb) } {
				if(peaks[i] >= 1.0) { this.clipColor } { this.peakColor }
			};
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
