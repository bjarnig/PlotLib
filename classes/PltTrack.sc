/*
Descriptors over time as stacked small multiples, sharing one clock, with onsets as
ticks through every panel. PltTrack.analysis reads a bus; push feeds it from
anywhere. A track given no lo and hi autoscales over what is visible.
*/
PltTrack : PltView {
	// one Event per track: (name:, lo:, hi:, unit:, colorKey:, points: List of [time, value])
	var <tracks;
	var <marks;                    // onset times
	var <>span = 10, <>panelGap = 10, <>maxPoints = 4000;
	var <server, <bus, <replyId;
	var synth, responder, onsetResponder, restartFunc;
	var <>rate = 20, <>fftSize = 1024, <>onsetThreshold = 0.5;

	classvar nextId = 0;

	// specs: Events like (name: "loudness", lo: 0, hi: 64, unit: "sones").
	// Without lo and hi, a track autoscales over what is visible.
	*new { |specs, span = 10, title = "tracks", width = 900, height = 460|
		^super.new(title, width, height).initPltTrack(specs, span)
	}

	// Loudness, spectral centroid, flatness and onsets, all from core UGens.
	*analysis { |bus = 0, server, span = 10, title = "analysis",
		width = 900, height = 520|
		^this.new([
			(name: "loudness", lo: 0, hi: 64, unit: "sones", colorKey: \ink),
			(name: "centroid", lo: 100, hi: 8000, unit: "Hz", colorKey: \mint),
			(name: "flatness", lo: 0, hi: 1, unit: "", colorKey: \gold)
		], span, title, width, height)
			.prSetAnalysis(bus, server ? Server.default)
	}

	initPltTrack { |specs, argSpan|
		var keys = [\ink, \mint, \gold, \rose, \trace];
		span = argSpan;
		marks = List.new;
		tracks = (specs ? [(name: "value")]).collect { |spec, i|
			(
				name: spec[\name] ? ("track" + i),
				lo: spec[\lo], hi: spec[\hi],
				unit: spec[\unit] ? "",
				colorKey: spec[\colorKey] ? keys[i % keys.size],
				points: List.new
			)
		};
		xLabel = "seconds";
		view.animate_(true).frameRate_(20);
		^this
	}

	prSetAnalysis { |argBus, argServer|
		bus = argBus;
		server = argServer;
		replyId = nextId;
		nextId = nextId + 1;
		restartFunc = { this.prRestart };
		ServerTree.add(restartFunc, server);
		if(server.serverRunning) { this.start };
		^this
	}

	start {
		if(synth.notNil or: { server.isNil }) { ^this };

		responder = OSCFunc({ |msg|
			if(msg[2] == replyId) { this.push(msg[3..]) };
		}, '/plt_track', server.addr);

		onsetResponder = OSCFunc({ |msg|
			if(msg[2] == replyId) { this.mark };
		}, '/plt_onset', server.addr);

		synth = {
			var in = In.ar(bus, 1);
			var descriptors = FFT(LocalBuf(fftSize), in);
			// Onsets needs its own chain: sharing one with the other three stops it
			// triggering at all, silently. 0 detections shared, 11 of 12 alone.
			var detector = FFT(LocalBuf(512), in);
			SendReply.kr(Impulse.kr(rate), '/plt_track', [
				Loudness.kr(descriptors),
				SpecCentroid.kr(descriptors),
				SpecFlatness.kr(descriptors)
			], replyId);
			SendReply.kr(Onsets.kr(detector, onsetThreshold), '/plt_onset', [1], replyId);
			DC.ar(0)
		}.play(RootNode(server), addAction: \addToTail);
		^this
	}

	stop {
		synth.free; synth = nil;
		responder.free; responder = nil;
		onsetResponder.free; onsetResponder = nil;
		^this
	}

	prRestart {
		synth = nil;
		responder.free; responder = nil;
		onsetResponder.free; onsetResponder = nil;
		this.start;
		^this
	}

	free {
		if(restartFunc.notNil) { ServerTree.remove(restartFunc, server) };
		this.stop;
		view.animate_(false);
		^this
	}

	// One frame: an Array in track order, or an Event keyed by track name.
	push { |values, time|
		var now = time ? Main.elapsedTime;
		tracks.do { |t, i|
			var v = if(values.isKindOf(Event)) { values[t[\name].asSymbol] } { values[i] };
			if(v.isNumber and: { v.isNaN.not }) {
				t[\points].add([now, v]);
				if(t[\points].size > maxPoints) { t[\points].removeAt(0) };
			};
		};
		^this
	}

	// An onset, drawn as a tick through every panel.
	mark { |time|
		marks.add(time ? Main.elapsedTime);
		if(marks.size > 500) { marks.removeAt(0) };
		^this
	}

	reset {
		tracks.do { |t| t[\points].clear };
		marks.clear;
		^this
	}

	// The visible range of one track: its own if given, otherwise what is on screen.
	rangeOf { |track, now|
		var vals, e;
		if(track[\lo].notNil and: { track[\hi].notNil }) {
			^[track[\lo], track[\hi]]
		};
		vals = track[\points].select { |p| p[0] >= (now - span) }.collect { |p| p[1] };
		e = PlotLib.extent(vals) ? #[0, 1];
		// a little air, so a flat line does not sit on the frame
		^[min(0, e[0]), e[1] + ((e[1] - e[0]) * 0.08)]
	}

	caption {
		^tracks.collect { |t|
			var last = t[\points].last;
			t[\name] ++ " " ++ if(last.isNil) { "-" } { last[1].round(0.01) }
		}.join(",  ")
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var now = Main.elapsedTime;
		var n = tracks.size;
		var h = (r.height - (panelGap * (n - 1))) / n;

		// drop what has scrolled out of the window
		tracks.do { |t|
			while({ t[\points].size > 0 and: { t[\points][0][0] < (now - span) } }, {
				t[\points].removeAt(0)
			});
		};
		while({ marks.size > 0 and: { marks[0] < (now - span) } }, { marks.removeAt(0) });

		tracks.do { |t, i|
			var panel = Rect(r.left, r.top + (i * (h + panelGap)), r.width, h);
			var range = this.rangeOf(t, now);
			// the axis is seconds ago, not elapsed time since sclang started
			var b = [span.neg, 0, range[0], range[1]];
			var last = i == (n - 1);
			this.drawPanel(panel, b, { this.prDrawTrack(t, panel, b, now) }, last, true);
			// the name inside the panel, since every panel has its own units
			Pen.stringLeftJustIn(" " ++ t[\name] ++ if(t[\unit].size > 0) {
				" (" ++ t[\unit] ++ ")"
			} { "" },
				Rect(panel.left, panel.top + 5, panel.width, 12),
				PlotLib.font(9), PlotLib.color(\muted));
		};

		this.prCaption(v, r);
	}

	prDrawTrack { |t, r, b, now|
		var pts = t[\points];

		// onsets first, so the trace stays on top of them
		if(marks.size > 0) {
			Pen.strokeColor = PlotLib.color(\rose).copy.alpha_(PlotLib.alphaFor(0.5));
			Pen.width = PlotLib.lineWidth(1);
			marks.do { |time|
				var px = this.xPix(time - now, r, b);
				Pen.line(Point(px, r.top), Point(px, r.bottom));
			};
			Pen.stroke;
		};

		if(pts.size < 2) { ^this };
		Pen.strokeColor = PlotLib.color(t[\colorKey]);
		Pen.width = PlotLib.lineWidth(1.2);
		pts.do { |p, i|
			var pos = Point(this.xPix(p[0] - now, r, b), this.yPix(p[1], r, b));
			if(i == 0) { Pen.moveTo(pos) } { Pen.lineTo(pos) };
		};
		Pen.stroke;
	}
}
