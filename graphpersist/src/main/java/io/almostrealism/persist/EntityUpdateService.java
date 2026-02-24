package io.almostrealism.persist;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EntityUpdateService {
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	
	public EntityUpdateService() { }
	
	public void submit(EntityUpdate e) {
		executor.submit(e);
	}
}
