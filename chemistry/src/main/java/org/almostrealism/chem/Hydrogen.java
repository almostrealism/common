package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Shell;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Hydrogen implements Element {
	public int getAtomicNumber() { return 1; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		return Collections.unmodifiableList(Arrays.asList(Shell.first(1)));
	}
}
