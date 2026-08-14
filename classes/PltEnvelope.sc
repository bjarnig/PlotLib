/*
An Env drawn with its breakpoints, its curve shapes and its release node.

	PltEnvelope(Env.perc(0.01, 0.4)).front;
	PltEnvelope(Env.adsr(0.02, 0.2, 0.4, 0.5)).front;

Env:plot already exists; this one is here for the same reason the rest of the
library is: axes in seconds and amplitude, the breakpoints marked, and the
release node shown, so a sustaining envelope reads differently from a fixed one.
*/
PltEnvelope : PltView {
	var <env, <times, <values;
	var <>curveColorKey = \ink, <>pointColorKey = \gold, <>showPoints = true;
	var <>resolution = 400;

	*new { |env, title = "envelope", width = 700, height = 300|
		^super.new(title, width, height).initPltEnvelope(env)
	}

	/*
	The curve as plain data: [times, values], sampled evenly so any curve type is
	drawn as the shape it actually is rather than as straight segments.

	The breakpoint times are added to the even samples, because a corner that
	falls between two samples is cut off: sampling Env.adsr 50 times returned a
	peak of 0.965 for an envelope that reaches 1.
	*/
	*points { |env, columns = 400|
		var dur = env.duration;
		var ts, vs, bp, t = 0;
		if(dur <= 0) { ^[[0], [env.levels.first]] };
		bp = [0] ++ env.times.collect { |d| t = t + d; t };
		ts = Array.fill(columns, { |i| i * dur / (columns - 1).max(1) });
		ts = (ts ++ bp).sort.select { |v| (v >= 0) and: { v <= dur } };
		vs = ts.collect { |time| env.at(time) };
		^[ts, vs]
	}

	initPltEnvelope { |argEnv|
		var pts;
		env = argEnv;
		pts = this.class.points(env, resolution);
		times = pts[0]; values = pts[1];
		xLabel = "seconds"; yLabel = "level";
		this.refresh;
		^this
	}

	env_ { |argEnv| this.initPltEnvelope(argEnv); ^this }

	// Cumulative time of each breakpoint, which is where the dots go.
	breakpointTimes {
		var t = 0;
		^[0] ++ env.times.collect { |d| t = t + d; t }
	}

	dataBounds {
		var levels = env.levels;
		var lo = min(0, levels.minItem), hi = levels.maxItem;
		if(hi <= lo) { hi = lo + 1 };
		^[0, env.duration.max(1e-6), lo, hi]
	}

	caption {
		var line = env.times.size.asString + "segments,"
			+ env.duration.round(0.001) ++ " s";
		if(env.releaseNode.notNil) {
			line = line ++ ", release at node" + env.releaseNode
		};
		if(env.loopNode.notNil) { line = line ++ ", loop from" + env.loopNode };
		^line
	}

	drawData { |v, r, b|
		var bpTimes = this.breakpointTimes;

		Pen.strokeColor = PlotLib.color(curveColorKey);
		Pen.width = PlotLib.lineWidth(1.5);
		values.do { |val, i|
			var p = Point(this.xPix(times[i], r, b), this.yPix(val, r, b));
			if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;

		// the release node, where a sustaining envelope waits for its gate
		if(env.releaseNode.notNil) {
			var t = bpTimes[env.releaseNode];
			if(t.notNil) {
				var px = this.xPix(t, r, b);
				Pen.strokeColor = PlotLib.color(\muted);
				Pen.width = PlotLib.lineWidth(1);
				Pen.line(Point(px, r.top), Point(px, r.bottom));
				Pen.stroke;
			};
		};

		if(showPoints) {
			Pen.fillColor = PlotLib.color(pointColorKey);
			env.levels.do { |lvl, i|
				var t = bpTimes[i];
				if(t.notNil) {
					Pen.addArc(Point(this.xPix(t, r, b), this.yPix(lvl, r, b)), 2.5, 0, 2pi);
				};
			};
			Pen.fill;
		};
	}
}
