/*
Tests for PltEvents, which needs a window and so runs on the AppClock.

	sclang tests/test-events.scd

The pitch cases are the point. Asking an Event for a key it does not hold returns
the unevaluated function its parent keeps, so a plotter that tests for nil draws
every degree-based pattern at one frequency and looks like it is working.
*/

var fails = 0, checks = 0, ok, near;

ok = { |name, cond|
	checks = checks + 1;
	if(cond) { ("  ok    " ++ name).postln } {
		fails = fails + 1;
		("  FAIL  " ++ name).postln;
	};
};
near = { |a, b, tol = 0.01| (a - b).abs <= tol };

Routine({
	var p = PltEvents("event tests", 8, 100, 2000);
	var freqsFrom;

	// run n events of a pattern through watch and return what was plotted
	freqsFrom = { |pattern, n|
		var st;
		p.reset;
		st = p.watch(pattern).asStream;
		n.do { st.next(Event.default.copy) };
		p.events.collect { |e| e[1] }
	};

	"--- watch resolves pitch however the pattern spells it ---".postln;
	ok.value("explicit freq passes through", {
		var f = freqsFrom.value(Pbind(\dur, 0.05, \freq, Pseq([200, 800])), 2);
		near.value(f[0], 200) and: { near.value(f[1], 800) }
	}.value);

	ok.value("degree becomes Hz through the default parent", {
		var f = freqsFrom.value(Pbind(\dur, 0.05, \degree, Pseq([0, 4, 7])), 3);
		near.value(f[0], 261.63) and: { near.value(f[1], 392.0) }
			and: { near.value(f[2], 523.25) }
	}.value);

	ok.value("midinote becomes Hz", {
		var f = freqsFrom.value(Pbind(\dur, 0.05, \midinote, Pseq([60, 72])), 2);
		near.value(f[0], 261.63) and: { near.value(f[1], 523.25) }
	}.value);

	ok.value("note becomes Hz", {
		var f = freqsFrom.value(Pbind(\dur, 0.05, \note, Pseq([0, 12])), 2);
		near.value(f[0], 261.63) and: { near.value(f[1], 523.25) }
	}.value);

	ok.value("distinct degrees give distinct frequencies", {
		// the failure this catches: everything at one frequency, drawn as a line
		freqsFrom.value(Pbind(\dur, 0.05, \degree, Pseq([0, 2, 4, 5, 7])), 5)
			.collect(_.round(0.01)).asSet.size == 5
	}.value);

	ok.value("amp comes from the event when set", {
		p.reset;
		p.watch(Pbind(\dur, 0.05, \freq, 400, \amp, 0.42)).asStream
			.next(Event.default.copy);
		near.value(p.events[0][2], 0.42, 1e-6)
	}.value);

	"--- push and the scrolling window ---".postln;
	ok.value("push stores time, frequency and amplitude", {
		var now = Main.elapsedTime;
		p.reset;
		p.push(440, 0.3, now);
		(p.events.size == 1) and: { p.events[0] == [now, 440.0, 0.3] }
	}.value);

	ok.value("an explicit time is kept, so a recorded sequence can be plotted", {
		var now = Main.elapsedTime;
		p.reset;
		10.do { |i| p.push(300 + i, 0.2, now - 4 + (i * 0.1)) };
		p.events.every { |e| e[0] < now }
	}.value);

	ok.value("maxEvents bounds the list", {
		p.reset;
		p.maxEvents = 50;
		200.do { p.push(440, 0.2) };
		p.events.size <= 51
	}.value);

	ok.value("reset empties it", { p.reset; p.events.size == 0 }.value);

	"--- window handling on the base class ---".postln;
	ok.value("alwaysOnTop returns the view, so it can end a chain",
		p.alwaysOnTop === p);
	0.4.wait;
	// Qt applies the flag when the window is shown, so this needs the wait
	ok.value("and the flag is set", p.isAlwaysOnTop);
	p.alwaysOnTop_(false);
	0.3.wait;
	ok.value("and can be turned off", p.isAlwaysOnTop.not);

	ok.value("position round trips", {
		p.position_(60 @ 120);
		0.3.wait;
		(p.position.x == 60) and: { p.position.y == 120 }
	}.value);

	p.close;
	0.3.wait;

	"".postln;
	("checks: " ++ checks ++ ", failures: " ++ fails).postln;
	if(fails > 0) { "*** FAILURES ***".postln } { "all event tests passed".postln };
	0.exit;
}).play(AppClock);
