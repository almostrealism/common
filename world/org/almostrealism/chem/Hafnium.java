package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Hafnium implements Element {
	public int getAtomicNumber() { return 72; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Xenon.getShells());
		s.add(Shell.fourth(0, 0, 0, 14));
		s.add(Shell.fifth(0, 0, 2, 0));
		s.add(Shell.sixth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
