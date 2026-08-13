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
