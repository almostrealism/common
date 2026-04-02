package io.almostrealism.persist;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serializes {@link EntityUpdate} operations onto a single background thread.
 *
 * <p>All submitted updates are queued and executed sequentially, ensuring that
 * concurrent callers do not produce conflicting database writes.</p>
 */
public class EntityUpdateService {
	/** Single-threaded executor that processes all submitted updates in order. */
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	/**
	 * Constructs a new {@link EntityUpdateService} with a single-threaded executor.
	 */
	public EntityUpdateService() { }

	/**
	 * Submits an {@link EntityUpdate} for asynchronous execution on the background thread.
	 *
	 * @param e The update to execute
	 */
	public void submit(EntityUpdate e) {
		executor.submit(e);
	}
}
