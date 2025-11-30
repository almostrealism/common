package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Element;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Manganese implements Element {
	public int getAtomicNumber() { return 25; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Argon.getShells());
		s.add(Shell.third(0, 0, 5));
		s.add(Shell.fourth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
