package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Uranium implements Element {
	public int getAtomicNumber() { return 92; }
	
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Radon.getShells());
		s.add(Shell.fifth(0, 0, 0, 3));
		s.add(Shell.sixth(0, 0, 1, 0));
		s.add(Shell.seventh(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
