package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Aluminium implements Element {
	public int getAtomicNumber() { return 13; }
	
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Neon.getShells());
		s.add(Shell.third(2, 1, 0));
		return Collections.unmodifiableList(s);
	}
}
