package org.almostrealism.chem;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Helium implements Element {
	public int getAtomicNumber() { return 2; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	protected List<Shell> getShells() {
		return Collections.unmodifiableList(Arrays.asList(Shell.first(2)));
	}
}
