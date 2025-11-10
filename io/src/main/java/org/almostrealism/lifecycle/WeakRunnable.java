package org.almostrealism.lifecycle;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A {@link Runnable} implementation that maintains a weak reference to a target object and performs
 * an operation on that target when run. The weak reference ensures that the target object can be
 * garbage collected if there are no other strong references to it.
 *
 * @param <T> The type of the target object to be referenced weakly
 */
public class WeakRunnable<T> extends WeakReference<T> implements Runnable {
	private Consumer<T> operation;

	/**
	 * Creates a new WeakRunnable with the specified target and operation.
	 *
	 * @param target    The object to be referenced weakly
	 * @param operation The operation to perform on the target when run
	 */
	public WeakRunnable(T target, Consumer<T> operation) {
		super(target);
		this.operation = operation;
	}

	/**
	 * Executes the operation on the target object if it has not been garbage collected.
	 * If the target has been collected, the operation will receive null.
	 */
	@Override
	public void run() { Stream.of(get()).filter(Objects::nonNull).forEach(operation); }
}