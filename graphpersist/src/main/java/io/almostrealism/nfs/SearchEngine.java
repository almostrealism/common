package io.almostrealism.nfs;

import io.almostrealism.resource.Resource;

public interface SearchEngine {
	Iterable<Resource> search(String path);
}
