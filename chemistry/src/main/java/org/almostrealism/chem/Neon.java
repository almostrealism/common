package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Element;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The Neon class. */
public class Neon implements Element {
	public int getAtomicNumber() { return 10; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	/** Performs the getShells operation. */
	protected List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 6));
		return Collections.unmodifiableList(s);
	}
}
