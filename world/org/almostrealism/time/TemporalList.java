package org.almostrealism.time;

import java.util.ArrayList;

public class TemporalList extends ArrayList<Temporal> implements Temporal {

	@Override
	public void tick() {
		forEach(Temporal::tick);
	}
}
