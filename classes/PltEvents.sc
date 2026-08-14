/*
Live event scatter: the last few seconds, one dot per event, log frequency up the
axis and amplitude as radius, over a histogram of what is visible. Feed it with
push, or wrap a pattern with watch.
*/
PltEvents : PltView {
	var <events, <>span, <>freqLo, <>freqHi, <>bins, <>maxEvents = 4000;
	var dotColor, barColor;
	var <>dotColorKey = \ink, <>barColorKey = \mint, <>split = 0.62;

	*new { |title = "events", span = 8, freqLo = 100, freqHi = 2000, bins = 40,
		width = 900, height = 460|
		^super.new(title, width, height).initPltEvents(span, freqLo, freqHi, bins)
	}

	initPltEvents { |argSpan, argLo, argHi, argBins|
		events = List.new;
		span = argSpan; freqLo = argLo; freqHi = argHi; bins = argBins;
		// x of the lower panel is Hz; the upper panel's time axis is stated in the caption
		xLabel = "Hz"; yLabel = "Hz";
		view.animate_(true).frameRate_(30);
	}

	// One event, stamped on arrival. Pass time to place it yourself, which is how
	// an already recorded sequence gets plotted.
	push { |freq, amp = 0.2, time|
		events.add([time ? Main.elapsedTime, freq.asFloat, amp.asFloat]);
		if(events.size > maxEvents) { events.removeAt(0) };
		^this
	}

	reset { events.clear; ^this }

	dotColor { ^dotColor ?? { PlotLib.color(dotColorKey) } }
	dotColor_ { |c| dotColor = c; ^this }
	barColor { ^barColor ?? { PlotLib.color(barColorKey) } }
	barColor_ { |c| barColor = c; ^this }

	free { view.animate_(false); ^this }

	// Wrap a pattern so playing it also plots it. Returns the wrapped pattern.
	watch { |pattern, freqKey = \freq, ampKey = \amp|
		^pattern.collect { |ev|
			this.push(this.prValueOf(ev, freqKey, 440), this.prValueOf(ev, ampKey, 0.2));
			ev
		}
	}

	// An Event asked for a key it lacks returns its parent's *unevaluated
	// function*, not nil, so compose with the default parent and evaluate inside.
	prValueOf { |ev, key, fallback|
		var v = ev.at(key), composed;
		if(v.isNumber.not) {
			composed = Event.default.copy.putAll(ev);
			v = try { composed.use { composed.at(key).value } };
		};
		v = v.asArray[0];
		^if(v.isNumber and: { v.isNaN.not }) { v } { fallback }
	}

	prDraw { |v|
		var r = this.plotRect(v);
		var now = Main.elapsedTime;
		var topRect, botRect, counts, visible, peak;

		// drop anything that has scrolled out of the window
		while({ events.size > 0 and: { events[0][0] < (now - span) } }, {
			events.removeAt(0)
		});
		visible = events.copy;

		topRect = Rect(r.left, r.top, r.width, (r.height * split).round);
		botRect = Rect(r.left, topRect.bottom + 12, r.width,
			(r.height - topRect.height - 12).max(1));

		this.drawPanel(topRect, [now - span, now, freqLo.max(1).log2, freqHi.log2],
			{
				Pen.fillColor = this.dotColor.copy.alpha_(PlotLib.alphaFor(0.65));
				visible.do { |e|
					var px = PlotLib.map(e[0], now - span, now, topRect.left, topRect.right);
					var py = PlotLib.map(e[1].max(1).log2, freqLo.max(1).log2, freqHi.log2,
						topRect.bottom, topRect.top);
					Pen.addArc(Point(px, py), 1.5 + (e[2].clip(0, 1) * 5), 0, 2pi);
				};
				Pen.fill;
			},
			false, false);

		// frequency axis of the top panel, labelled in Hz rather than in log2
		this.prFreqLabels(topRect);

		counts = PlotLib.histogram(visible.collect { |e| e[1] }, freqLo, freqHi, bins);
		peak = counts.maxItem.max(1);
		this.drawPanel(botRect, [freqLo, freqHi, 0, peak],
			{
				var bw = botRect.width / bins;
				Pen.fillColor = this.barColor.copy.alpha_(PlotLib.alphaFor(0.8));
				counts.do { |c, i|
					var bh = if(c > 0) { ((c / peak) * botRect.height).max(1) } { 0 };
					if(bh > 0) {
						Pen.addRect(Rect(botRect.left + (i * bw), botRect.bottom - bh,
							(bw - 1).max(1), bh));
					};
				};
				Pen.fill;
			});

		this.prCaption(v, r);
	}

	prFreqLabels { |r|
		var font = PlotLib.font(9), col = PlotLib.color(\muted);
		var lo = freqLo.max(1), hi = freqHi;
		// octave lines are the readable divisions of a log axis
		(lo.log2.ceil.asInteger .. hi.log2.floor.asInteger).do { |oct|
			var f = 2 ** oct;
			var py = PlotLib.map(oct, lo.log2, hi.log2, r.bottom, r.top);
			Pen.stringRightJustIn(f.asInteger.asString,
				Rect(r.left - padLeft, py - 6, padLeft - 7, 12), font, col);
		};
	}

	caption { ^events.size.asString + "events, last" + span + "s" }
}
