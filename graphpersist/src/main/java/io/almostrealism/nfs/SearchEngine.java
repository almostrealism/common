package io.almostrealism.nfs;

import io.almostrealism.resource.Resource;

/** The SearchEngine interface. */
public interface SearchEngine {
	/** Performs the search operation. */
	Iterable<Resource> search(String path);
}
