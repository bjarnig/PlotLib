/*
Check the help: that every page parses, that every class has one, and that every
code example in the help files and in examples/ compiles.

A page that does not parse is invisible: SCDoc writes no HTML for it and the help
browser answers ERR_FILE_NOT_FOUND. One unterminated strong:: did that to
PltMeter, and nothing else noticed.

	sclang tests/check-docs.scd

An example that does not compile is a defect in the documentation, and nothing
else notices it: schelp renders happily around broken code. This does not run the
examples, only compile them, so nothing opens a window or boots a server.

Each block is split the way the IDE evaluates it: a balanced ( ... ) group
starting at column 0, or one line at a time. A multi-line statement that is not
wrapped in parentheses fails here, which is correct, since it cannot be evaluated
from the help browser either.
*/

var root = thisProcess.nowExecutingPath.absolutePath.dirname.dirname;
var files, blocks, units, stripBlockComments, fails = 0, checks = 0;

// a /* */ comment is not a unit; splitting per line would try to compile its prose
stripBlockComments = { |text|
	var out = "", i = 0, size = text.size;
	while({ i < size }, {
		if((text[i] == $/) and: { (i + 1) < size } and: { text[i + 1] == $* }) {
			i = i + 2;
			while({ (i < size) and: {
				(text[i] != $*) or: { ((i + 1) < size) and: { text[i + 1] != $/ } }
			} }, { i = i + 1 });
			i = i + 2;
		} {
			out = out ++ text[i];
			i = i + 1;
		};
	});
	out
};

// every ( ... ) group or single line in a snippet, as the IDE would take it
units = { |src|
	var lines = src.split($\n), out = List.new, i = 0;
	while({ i < lines.size }, {
		var line = lines[i], depth = 0, buf = List.new, done = false;
		if(line.beginsWith("(")) {
			while({ (i < lines.size) and: { done.not } }, {
				// count takes a predicate, not an element; occurrencesOf takes the element
				depth = depth + lines[i].occurrencesOf($() - lines[i].occurrencesOf($));
				buf.add(lines[i]);
				i = i + 1;
				if(depth <= 0) { done = true };
			});
			out.add(buf.join("\n"));
		} {
			if(line.stripWhiteSpace.isEmpty.not and: {
				line.stripWhiteSpace.beginsWith("//").not
			}) { out.add(line) };
			i = i + 1;
		};
	});
	out.asArray
};

// code:: ... :: blocks of a schelp file, and the whole text of an .scd
blocks = { |path|
	var text = File.readAllString(path), out = List.new;
	if(path.endsWith(".scd")) {
		out.add(stripBlockComments.value(text))
	} {
		var lines = text.split($\n), i = 0;
		while({ i < lines.size }, {
			if(lines[i].stripWhiteSpace == "code::") {
				var buf = List.new;
				i = i + 1;
				while({ (i < lines.size) and: { lines[i].stripWhiteSpace != "::" } }, {
					buf.add(lines[i]); i = i + 1;
				});
				// a class definition in a help file is illustration, not evaluable
				if(buf.join("\n").findRegexp("(?m)^[A-Z]\\w*\\s*:\\s*[A-Z]\\w*\\s*\\{").isEmpty) {
					out.add(buf.join("\n"));
				};
			};
			i = i + 1;
		});
	};
	out.asArray
};

files = ["HelpSource/Classes", "HelpSource/Overviews", "examples"].collect { |dir|
	(root +/+ dir +/+ "*").pathMatch
}.flatten.select { |p| p.endsWith(".schelp") or: { p.endsWith(".scd") } };

("checking " ++ files.size ++ " files").postln;

// --- every schelp parses, and every class has one ---
{
	var helpDirs = [root +/+ "HelpSource/Classes", root +/+ "HelpSource/Overviews"];
	var pages = helpDirs.collect { |d| (d +/+ "*.schelp").pathMatch }.flatten;
	var documented, missing;

	pages.do { |path|
		var name = PathName(path).fileName;
		var doc = try { SCDoc.parseFileFull(path) };
		checks = checks + 1;
		if(doc.isNil) {
			fails = fails + 1;
			("  FAIL  " ++ name ++ " does not parse, so no help page is generated").postln;
		};
		// SCDoc wants a property setter documented under its plain name
		File.readAllString(path).split($\n).do { |line|
			if(line.beginsWith("METHOD:: ") and: { line.stripWhiteSpace.endsWith("_") }) {
				checks = checks + 1;
				fails = fails + 1;
				("  FAIL  " ++ name ++ ": " ++ line.stripWhiteSpace
					++ " should be documented without the underscore").postln;
			};
		};
	};

	documented = pages.collect { |p| PathName(p).fileNameWithoutExtension };
	missing = PlotLib.classes.collect { |c| c.name.asString }
		.reject { |n| documented.includesEqual(n) };
	checks = checks + 1;
	if(missing.size > 0) {
		fails = fails + 1;
		("  FAIL  no help file for: " ++ missing.join(", ")).postln;
	};
}.value;

files.do { |path|
	var name = PathName(path).fileName;
	blocks.value(path).do { |src|
		units.value(src).do { |unit|
			var fn = try { unit.compile };
			checks = checks + 1;
			if(fn.isNil) {
				fails = fails + 1;
				("  FAIL  " ++ name ++ ":  " ++ unit.split($\n).first).postln;
			};
		};
	};
};

("units compiled: " ++ checks ++ ", failures: " ++ fails).postln;
if(fails > 0) { "*** FAILURES ***".postln } { "every documented example compiles".postln };
0.exit;
