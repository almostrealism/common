/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio;

import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of every {@link AudioScene} that has been constructed and not yet
 * {@link AudioScene#destroy() destroyed}, held by {@link WeakReference} so a scene a caller
 * abandons without destroying is not pinned alive by it.
 *
 * <p>The entries exist only to drive {@link AudioScene#destroyAll()} and to gate the shared
 * compiled-renderer cache reclamation on there being no live scene; nothing reads scene
 * state through them, so holding them weakly costs no correctness. A scene still in use is
 * strongly reachable through its user and so is never collected here. An abandoned scene,
 * by contrast, becomes unreachable, and only once it is unreachable can the native memory it
 * owns — its consolidated render buffers, pattern caches, and delay lines — be reclaimed
 * through the hardware memory providers' {@link java.lang.ref.PhantomReference} queues. A
 * strong reference here would keep every scene ever built resident for the whole process,
 * which in a long-lived reuse fork (a test fork that builds many scenes across methods
 * without explicit cleanup, a batch of offline renders) accumulates without bound.</p>
 *
 * <p>All operations synchronize on the registry, so scenes may be built and destroyed on
 * different threads.</p>
 */
class AudioSceneRegistry {
	/** Weak references to the registered scenes. */
	private final List<WeakReference<AudioScene<?>>> scenes = new ArrayList<>();

	/**
	 * Adds a scene under a weak reference, first dropping any entries whose scene has
	 * already been garbage collected.
	 *
	 * <p>Purging on registration keeps the registry tracking only live scenes rather than
	 * accumulating a dead {@link WeakReference} for every scene ever abandoned without being
	 * destroyed, which would otherwise grow without bound across a long-lived reuse fork.
	 * The shared compiled-renderer cache is a separate concern: it is reclaimed on the
	 * explicit {@link #deregister deregistration} of the last live scene, never on garbage
	 * collection, so that it can outlive any single scene for cross-scene kernel reuse.</p>
	 *
	 * @param scene the scene to register
	 */
	synchronized void register(AudioScene<?> scene) {
		scenes.removeIf(ref -> ref.get() == null);
		scenes.add(new WeakReference<>(scene));
	}

	/**
	 * Removes a scene, dropping its entry along with any whose referent has already been
	 * garbage collected, and reclaims the shared compiled-renderer cache
	 * ({@link BatchedPatternLayerRenderer#clearCompiledRenderers()}) once no live scene
	 * remains.
	 *
	 * <p>Purging the cleared entries is what lets the emptiness test become true again after
	 * scenes are abandoned rather than explicitly destroyed: a cleared weak reference means
	 * its scene is already unreachable and being reclaimed, so it no longer counts as live.
	 * The remaining entries all have live referents, so an empty registry means no scene is
	 * live and the shared renderer cache can be dropped.</p>
	 *
	 * @param scene the scene to deregister
	 */
	synchronized void deregister(AudioScene<?> scene) {
		scenes.removeIf(ref -> {
			AudioScene<?> registered = ref.get();
			return registered == null || registered == scene;
		});

		if (scenes.isEmpty()) {
			BatchedPatternLayerRenderer.clearCompiledRenderers();
		}
	}

	/**
	 * Returns the registered scenes that have not been garbage collected, as a snapshot
	 * the caller may act on (for example destroy) without holding the registry.
	 *
	 * @return the live registered scenes
	 */
	synchronized List<AudioScene<?>> liveScenes() {
		List<AudioScene<?>> live = new ArrayList<>();
		for (WeakReference<AudioScene<?>> ref : scenes) {
			AudioScene<?> scene = ref.get();
			if (scene != null) live.add(scene);
		}
		return live;
	}
}
