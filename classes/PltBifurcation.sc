/*
Bifurcation diagram of a one dimensional map: sweep the parameter along x and plot
the attractor vertically. mapFunc takes (x, parameter) and returns the next x.
*/
PltBifurcation : PltScatter {
	var <mapFunc, <paramLo, <paramHi, <x0;

	*new { |mapFunc, paramLo = 2.4, paramHi = 4.0, x0 = 0.5,
		columns = 460, transient = 200, keep = 90,
		title = "bifurcation", width = 900, height = 460|
		var pts = this.points(mapFunc, paramLo, paramHi, x0, columns, transient, keep);
		^super.new(pts[0], pts[1], title, width, height)
			.prSetMap(mapFunc, paramLo, paramHi, x0)
	}

	// [params, values] as two FloatArrays. Each column runs from x0, discards
	// transient iterations so only the attractor is left, then records keep.
	*points { |mapFunc, paramLo = 2.4, paramHi = 4.0, x0 = 0.5,
		columns = 460, transient = 200, keep = 90|
		var params = FloatArray.new(columns * keep);
		var values = FloatArray.new(columns * keep);

		columns.do { |i|
			var r = paramLo + ((paramHi - paramLo) * (i / (columns - 1).max(1)));
			var x = x0;
			transient.do { x = mapFunc.value(x, r) };
			keep.do {
				x = mapFunc.value(x, r);
				if(x.isNumber and: { x.isNaN.not and: { x.abs < 1e6 } }) {
					params = params.add(r);
					values = values.add(x);
				}
			};
		};
		^[params, values]
	}

	prSetMap { |argFunc, argLo, argHi, argX0|
		mapFunc = argFunc; paramLo = argLo; paramHi = argHi; x0 = argX0;
		dotColorKey = \ink;
		alpha = 0.35;
		xLabel = "parameter"; yLabel = "x";
		^this
	}

	// Recompute over a new parameter window, keeping the same map.
	zoom { |newLo, newHi, columns = 460, transient = 200, keep = 90|
		var pts;
		paramLo = newLo; paramHi = newHi;
		pts = this.class.points(mapFunc, paramLo, paramHi, x0, columns, transient, keep);
		this.data_(pts[0], pts[1]);
		^this
	}
}
