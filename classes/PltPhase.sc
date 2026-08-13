/*
Phase portrait of a two dimensional map: x against y, so an attractor shows as
a shape rather than as a time series.

	PltPhase({ |x, y| [1 - (1.4 * x * x) + y, 0.3 * x] }, 0.1, 0.1, "Henon").front;

mapFunc takes x and y and returns the next pair.
*/
PltPhase : PltScatter {
	var <mapFunc, <x0, <y0;

	*new { |mapFunc, x0 = 0.1, y0 = 0.1, title = "phase portrait",
		transient = 400, keep = 12000, width = 700, height = 700|
		var pts = this.points(mapFunc, x0, y0, transient, keep);
		^super.new(pts[0], pts[1], title, width, height).prSetMap(mapFunc, x0, y0)
	}

	// Returns [xs, ys] as two FloatArrays. Stops early if the orbit escapes.
	*points { |mapFunc, x0 = 0.1, y0 = 0.1, transient = 400, keep = 12000, limit = 1e6|
		var xs = FloatArray.new(keep), ys = FloatArray.new(keep);
		var x = x0, y = y0, pair;

		transient.do {
			pair = mapFunc.value(x, y);
			x = pair[0]; y = pair[1];
		};

		// block/break: lowering the count cannot stop a do loop already given it
		block { |break|
			keep.do {
				pair = mapFunc.value(x, y);
				x = pair[0]; y = pair[1];
				if(x.isNaN or: { y.isNaN } or: { x.abs > limit } or: { y.abs > limit }) {
					break.value    // escaped; stop rather than plot nonsense
				};
				xs = xs.add(x); ys = ys.add(y);
			};
		};
		^[xs, ys]
	}

	prSetMap { |argFunc, argX0, argY0|
		mapFunc = argFunc; x0 = argX0; y0 = argY0;
		dotColorKey = \mint;
		alpha = 0.3;
		// each axis is scaled to fill the frame, as published attractor figures
		// are; equalAspect_(true) when the true proportions matter
		equalAspect = false;
		xLabel = "x"; yLabel = "y";
		^this
	}

	// Rerun from a different seed, keeping the same map.
	seed { |newX0, newY0, transient = 400, keep = 12000|
		var pts;
		x0 = newX0; y0 = newY0;
		pts = this.class.points(mapFunc, x0, y0, transient, keep);
		this.data_(pts[0], pts[1]);
		^this
	}
}
