package io.almostrealism.code;

import org.almostrealism.io.Resource;
import org.almostrealism.util.StaticProducer;

/**
 * A {@link ResourceArgument} is an {@link Argument} implementation
 * that takes data from a {@link Resource} and provides it as an
 * {@link Argument}.
 *
 * @param <T>  Type of the underlying data.
 */
public class ResourceArgument<T> extends Argument<T> {
	private Resource<T> res;

	/**
	 * Create a {@link ResourceArgument} using the data from the specified {@link Resource}.
	 *
	 * @see  Resource#getData()
	 */
	public ResourceArgument(String name, Resource<T> r) {
		super(name, StaticProducer.of(r.getData()));
		this.res = r;
	}

	public Resource<T> getResource() { return res; }
}
