package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Neon implements Element {
	public int getAtomicNumber() { return 10; }

	public Atom construct() { return new Atom(getShells()); }
	
	protected List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 6));
		return Collections.unmodifiableList(s);
	}
}
