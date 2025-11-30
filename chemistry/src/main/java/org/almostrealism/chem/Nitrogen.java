package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Element;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Nitrogen implements Element {
	public int getAtomicNumber() { return 7; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 3));
		return Collections.unmodifiableList(s);
	}
}
