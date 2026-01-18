package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public class CollectionReceptor implements Receptor<PackedCollection> {
	private final PackedCollection dest;
	private final Producer<PackedCollection> pos;
	private final Runnable r;

	public CollectionReceptor(PackedCollection dest) {
		this(dest, null);
	}

	public CollectionReceptor(PackedCollection dest, Producer<PackedCollection> pos) {
		this(dest, pos, null);
	}

	public CollectionReceptor(PackedCollection dest, Producer<PackedCollection> pos, Runnable r) {
		if (pos != null && dest.getShape().getDimensions() != 2)
			throw new IllegalArgumentException();

		this.dest = dest;
		this.pos = pos;
		this.r = r;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		return () -> () -> {
			PackedCollection in = protein.get().evaluate();
			int p = pos == null ? 0 : (int) pos.get().evaluate().toDouble(0);

			int length = pos == null ? dest.getShape().getTotalSize() : dest.getShape().length(1);
			int offset = p * length;

			dest.setMem(offset, in, 0, length);
			if (r != null) r.run();
		};
	}
}
