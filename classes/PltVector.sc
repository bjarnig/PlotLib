/*
Vectorscope: one channel against the other, which is how stereo width and phase
become visible. Identical channels draw a vertical line, opposite ones a horizontal
line, unrelated ones a cloud; the diagonals are the mid and side axes.

Older frames are kept and drawn fainter, which is what makes a moving image
readable rather than a flicker.
*/
PltVector : PltView {
	var <xs, <ys, <trails;
	var <bus, <server, <frames;
	var buffer, synth, poller, restartFunc;
	var <>dotColorKey = \ink, <>guideColorKey = \edge;
	var <>alpha = 0.5, <>dotSize = 1.5, <>persistence = 6, <>pollRate = 20;

	*new { |left, right, title = "vector", width = 480, height = 480|
		^super.new(title, width, height).initPltVector(left, right)
	}

	// The first two channels of a soundfile. A mono file plots against itself,
	// which is the diagonal, and says so.
	*fromSoundFile { |path, maxFrames = 40000, title, width = 480, height = 480|
		var sf = SoundFile.openRead(path.standardizePath);
		var data, chans, count, stride, left, right;
		if(sf.isNil) { ("PltVector: cannot read" + path).warn; ^nil };
		chans = sf.numChannels;
		data = FloatArray.newClear(sf.numFrames * chans);
		sf.readData(data);
		sf.close;
		count = data.size div: chans;
		stride = (count / maxFrames).ceil.asInteger.max(1);
		left = Array.new((count div: stride) + 1);
		right = Array.new((count div: stride) + 1);
		forBy(0, count - 1, stride) { |f|
			left = left.add(data[f * chans]);
			right = right.add(data[(f * chans) + (chans - 1).min(1)]);
		};
		^this.new(left, right, title ?? { PathName(path).fileName }, width, height)
	}

	// Live, from two adjacent busses, through a looping RecordBuf.
	*live { |bus = 0, server, frames = 2048, title = "vector", width = 480, height = 480|
		^super.new(title, width, height)
			.initPltVector(nil, nil)
			.prSetLive(bus, server ? Server.default, frames)
	}

	// 1 identical, 0 unrelated, -1 inverted. About zero rather than the mean: a DC
	// offset is a fault in audio, not something to remove from the reading.
	*correlation { |left, right|
		var n = left.size.min(right.size), sxy = 0, sxx = 0, syy = 0;
		if(n == 0) { ^0 };
		n.do { |i|
			var a = left[i], b = right[i];
			sxy = sxy + (a * b);
			sxx = sxx + (a * a);
			syy = syy + (b * b);
		};
		if((sxx * syy) <= 0) { ^0 };
		^sxy / sqrt(sxx * syy)
	}

	initPltVector { |left, right|
		xs = left ? [0];
		ys = right ? [0];
		trails = List.new;
		xLabel = "left"; yLabel = "right";
		showTicks = false;      // the guides carry the geometry, ticks only clutter
		this.refresh;
		^this
	}

	prSetLive { |argBus, argServer, argFrames|
		bus = argBus;
		server = argServer;
		frames = argFrames;
		restartFunc = { this.prRestart };
		ServerTree.add(restartFunc, server);
		if(server.serverRunning) { this.start };
		^this
	}

	start {
		if(poller.notNil or: { server.isNil }) { ^this };
		poller = Routine({
			var wait = 1 / pollRate.max(1);
			buffer = Buffer.alloc(server, frames, 2);
			server.sync;
			synth = {
				RecordBuf.ar(In.ar(bus, 2), buffer.bufnum, loop: 1);
				DC.ar(0)
			}.play(RootNode(server), addAction: \addToTail);
			server.sync;
			loop {
				buffer.getToFloatArray(wait: 0.01, timeout: 2, action: { |data|
					if(data.notNil and: { poller.notNil }) {
						// interleaved two channel buffer
						var n = data.size div: 2;
						this.pushFrame(
							Array.fill(n, { |i| data[i * 2] }),
							Array.fill(n, { |i| data[(i * 2) + 1] })
						);
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
		^this
	}

	// One frame of two channels. Older frames are kept and drawn fainter, which
	// is what makes a moving image readable rather than a flicker.
	pushFrame { |left, right|
		trails.addFirst([xs, ys]);
		while({ trails.size > persistence }, { trails.pop });
		xs = left;
		ys = right;
		this.refresh;
		^this
	}

	correlation { ^this.class.correlation(xs, ys) }

	dataBounds { ^[-1, 1, -1, 1] }

	caption {
		^"correlation" + this.correlation.round(0.01) ++ "," + xs.size + "frames"
	}

	drawData { |v, r, b|
		var col = PlotLib.color(dotColorKey);
		var plot = { |x, y, a|
			Pen.fillColor = col.copy.alpha_(PlotLib.alphaFor(a));
			x.size.min(y.size).do { |i|
				Pen.addRect(Rect(this.xPix(x[i], r, b), this.yPix(y[i], r, b),
					dotSize, dotSize));
			};
			Pen.fill;
		};

		// mid and side axes
		Pen.strokeColor = PlotLib.color(guideColorKey);
		Pen.width = PlotLib.lineWidth(1);
		Pen.line(Point(r.left, r.bottom), Point(r.right, r.top));
		Pen.line(Point(r.left, r.top), Point(r.right, r.bottom));
		Pen.line(Point(r.left, (r.top + r.bottom) / 2), Point(r.right, (r.top + r.bottom) / 2));
		Pen.line(Point((r.left + r.right) / 2, r.top), Point((r.left + r.right) / 2, r.bottom));
		Pen.stroke;

		trails.do { |pair, i|
			plot.value(pair[0], pair[1], alpha * (1 - ((i + 1) / (persistence + 1))) * 0.6);
		};
		plot.value(xs, ys, alpha);
	}
}
