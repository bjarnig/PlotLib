/*
Chaotic maps and distributions, plotted.

Evaluate the blocks one at a time. Nothing here needs the server.
*/

// The logistic map: one fixed point, then two, then four, then chaos.
// The marker says where in the diagram a given parameter value sits.
(
~logistic = { |x, r| r * x * (1 - x) };
~bif = PltBifurcation(~logistic, 2.4, 4.0, 0.5, 700, 300, 120, "logistic map")
	.marker_(3.82)
	.front;
)

// The structure is self similar: zoom into the first period-doubling window
~bif.zoom(3.53, 3.6);
~bif.zoom(3.847, 3.857);
~bif.zoom(2.4, 4.0);

// Drag the marker along and watch where you are
(
Routine({
	(2.4, 2.41 .. 4.0).do { |r| ~bif.marker_(r); 0.03.wait };
}).play(AppClock);
)


// The tent map, which reaches chaos without the smooth doublings
(
PltBifurcation({ |x, r| if(x <= 0.5, { 2 * r * x }, { 2 * r * (1 - x) }) },
	0.4, 1.0, 0.25, 700, 300, 120, "tent map").front;
)

// The sine map: the same route to chaos from a different function
(
PltBifurcation({ |x, r| r * sin(pi * x) }, 0.5, 1.0, 0.5, 700, 300, 120,
	"sine map").front;
)


// Phase portraits: two dimensional maps, drawn as the shape they settle into.
// The seed matters. A seed off the attractor gives one point or an escape.
PltPhase({ |x, y| [1 - (1.4 * x * x) + y, 0.3 * x] }, 0.1, 0.1, "Henon").front;

PltPhase({ |x, y| [1 - y + x.abs, x] }, -0.1, 0, "gingerbreadman").front;

(
PltPhase({ |x, y|
	var t = 0.4 - (6 / (1 + (x * x) + (y * y)));
	[1 + (0.9 * ((x * cos(t)) - (y * sin(t)))), 0.9 * ((x * sin(t)) + (y * cos(t)))]
}, 0.1, 0.1, "Ikeda", 400, 30000).front;
)

// Equal aspect, for a map whose proportions are part of the picture
(
PltPhase({ |x, y|
	var ny = y + (0.971635 * sin(x));
	[(x + ny) % 2pi, ny]
}, 0.1, 1.0, "standard map").equalAspect_(true).front;
)


// Distributions. What a generator produces is often not what its name suggests.
PltHistogram({ 1.0.rand } ! 8000, 0, 1, 40, "uniform").front;

PltHistogram({ exprand(0.01, 1.0) } ! 8000, 0, 1, 40, "exponential").front;

// Sum three uniforms and the spread is one; average them and it is a third.
// Two histograms side by side is the quickest way to see that.
(
PltHistogram({ ({ 1.0.rand } ! 3).sum / 3 } ! 8000, 0, 1, 40, "mean of three").front;
PltHistogram({ ({ 1.0.rand } ! 3).sum } ! 8000, 0, 3, 40, "sum of three").front;
)

// A brownian walk, as a scatter against its own index
(
~walk = Array.fill(4000, { 0 });
(1 .. ~walk.size - 1).do { |i| ~walk[i] = (~walk[i - 1] + 0.1.rand2).clip(-1, 1) };
PltScatter((0 .. ~walk.size - 1), ~walk, "brownian walk").dotSize_(2).alpha_(0.7).front;
)

// and the distribution of where it spent its time
PltHistogram(~walk, -1, 1, 40, "time spent").front;


// ---- densities, chains and filters ----

// What a generator actually produces, against the density it is supposed to have.
// exprand is log-uniform: its density falls as 1/x. It is not exponential.
PltDistribution({ exprand(0.01, 1.0) } ! 20000, 0.01, 1, 40, \logUniform).front;

// The same samples against a flat density, which they are not
PltDistribution({ exprand(0.01, 1.0) } ! 20000, 0.01, 1, 40, \uniform).front;

// rand is flat, and the deviation in the caption says so
PltDistribution({ 1.0.rand } ! 20000, 0, 1, 40, \uniform).front;

// The mean of three uniforms is nearly gaussian with a third of the spread, which
// is the trap in using it as a "random" value
(
PltDistribution({ ({ 1.0.rand } ! 3).sum / 3 } ! 20000, 0, 1, 40,
	PltDistribution.density(\gauss, [0.5, 0.167]), "mean of three").front;
)

// Any function works as a density, unnormalised
PltDistribution({ 1.0.rand.squared } ! 20000, 0, 1, 40, { |x| 1 / x.sqrt }).front;


// A Markov chain as a picture: brightness is probability, the bars under the
// columns are where the chain spends its time, the bits are how free each state is
(
~chain = [
	[0.1, 0.7, 0.2, 0.0],
	[0.3, 0.1, 0.4, 0.2],
	[0.0, 0.2, 0.5, 0.3],
	[0.6, 0.0, 0.0, 0.4]
];
PltMarkov(~chain, ["kick", "snare", "hat", "rest"], "four state chain").front;
)

// Counted from what a pattern actually played
(
~played = Pseq([0, 2, 4, 7, 4, 2], 6).asStream.all;
PltMarkov.fromSequence(~played, nil, "degrees played").front;
)


// Filter responses. SC's SOS carries the sign in its recursive terms, so a
// textbook biquad needs a1 and a2 negated.
(
~rbj = { |freq, q|
	var w0 = 2pi * freq / 48000;
	var alpha = sin(w0) / (2 * q);
	var b0 = (1 - cos(w0)) / 2, b1 = 1 - cos(w0);
	var a0 = 1 + alpha, a1 = -2 * cos(w0), a2 = 1 - alpha;
	[b0 / a0, b1 / a0, b0 / a0, a1.neg / a0, a2.neg / a0]
};
~f = PltFilter(~rbj.value(800, 0.707), "Q 0.707", 48000, "lowpass at 800 Hz");
~f.add(~rbj.value(800, 2), "Q 2");
~f.add(~rbj.value(800, 8), "Q 8");
~f.front;
)

// The textbook two point filters, which have no resonance at all
(
~f = PltFilter([0.5, 0.5, 0, 0, 0], "averager", 48000, "two point filters");
~f.add([1, -1, 0, 0, 0], "difference");
~f.add([0, 1, 0, 0, 0], "one sample delay");
~f.front;
)

// Measured from a UGen graph, which needs the server
s.boot;
PltFilter.fromFunction({ |in| BPF.ar(in, 1000, 0.1) }, action: { |p| p.front });
