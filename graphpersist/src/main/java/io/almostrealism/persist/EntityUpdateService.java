package io.almostrealism.persist;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The EntityUpdateService class. */
public class EntityUpdateService {
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	
	public EntityUpdateService() { }
	
	/** Performs the submit operation. */
	public void submit(EntityUpdate e) {
		executor.submit(e);
	}
}
