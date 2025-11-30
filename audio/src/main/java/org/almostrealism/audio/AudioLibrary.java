/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveDetailsJob;
import org.almostrealism.concurrent.SuspendableThreadPoolExecutor;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AudioLibrary implements ConsoleFeatures {
	public static double BACKGROUND_PRIORITY = 0.0;
	public static double DEFAULT_PRIORITY = 0.5;
	public static double HIGH_PRIORITY = 1.0;

	private final FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> root;
	private final int sampleRate;

	private final Map<String, String> identifiers;
	private final Map<String, WaveDetails> info;

	private final WaveDetailsFactory factory;
	private final PriorityBlockingQueue<WaveDetailsJob> queue;
	private int totalJobs;

	private DoubleConsumer progressListener;
	private Consumer<Exception> errorListener;
	private SuspendableThreadPoolExecutor executor;

	public AudioLibrary(File root, int sampleRate) {
		this(new FileWaveDataProviderNode(root), sampleRate);
	}

	public AudioLibrary(FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> root, int sampleRate) {
		this.root = root;
		this.sampleRate = sampleRate;
		this.identifiers = new HashMap<>();
		this.info = new HashMap<>();
		this.factory = new WaveDetailsFactory(sampleRate);
		this.queue = new PriorityBlockingQueue<>(100,
				Comparator.comparing(WaveDetailsJob::getPriority).reversed());

		start();
	}

	public void start() {
		if (executor != null) return;

		executor = new SuspendableThreadPoolExecutor(1, 1,
				60, TimeUnit.MINUTES, queue);
		executor.setPriority(job -> ((WaveDetailsJob) job).getPriority());
	}

	public boolean isPaused() {
		return executor == null || executor.getPriorityThreshold() >= HIGH_PRIORITY;
	}

	public void pause() {
		if (executor != null) {
			executor.setPriorityThreshold(HIGH_PRIORITY);
		}
	}

	public void resume() {
		executor.resumeAllTasks();
	}

	public FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> getRoot() {
		return root;
	}

	public int getSampleRate() { return sampleRate; }

	public DoubleConsumer getProgressListener() { return progressListener; }
	public void setProgressListener(DoubleConsumer progressListener) {
		this.progressListener = progressListener;
	}

	public Consumer<Exception> getErrorListener() { return errorListener; }
	public void setErrorListener(Consumer<Exception> errorListener) {
		this.errorListener = errorListener;
	}

	public WaveDetailsFactory getWaveDetailsFactory() { return factory; }

	public int getTotalJobs() { return totalJobs; }
	public int getPendingJobs() { return queue.size(); }

	public Stream<WaveDetails> allDetails() {
		return new ArrayList<>(info.keySet()).stream().map(info::get).filter(Objects::nonNull);
	}

	public Collection<WaveDetails> getAllDetails() {
		return allDetails().toList();
	}

	public WaveDetails get(String identifier) { return info.get(identifier); }

	public WaveDataProvider find(String identifier) {
		return root.children()
				.map(Supplier::get)
				.filter(Objects::nonNull)
				.filter(f -> Objects.equals(identifier, f.getIdentifier()))
				.findFirst()
				.orElse(null);
	}

	public void include(WaveDetails details) {
		if (details.getIdentifier() == null) {
			throw new IllegalArgumentException();
		}

		info.put(details.getIdentifier(), details);
	}

	public Optional<WaveDetails> getDetailsNow(String key) {
		return getDetailsNow(new FileWaveDataProvider(key));
	}

	public Optional<WaveDetails> getDetailsNow(String key, boolean persistent) {
		return getDetailsNow(new FileWaveDataProvider(key), persistent);
	}

	public Optional<WaveDetails> getDetailsNow(WaveDataProvider provider) {
		return getDetailsNow(provider, false);
	}

	public Optional<WaveDetails> getDetailsNow(WaveDataProvider provider, boolean persistent) {
		return Optional.ofNullable(getDetails(provider, persistent, DEFAULT_PRIORITY).getNow(null));
	}

	public WaveDetails getDetailsAwait(String key, boolean persistent) {
		return getDetailsAwait(new FileWaveDataProvider(key), persistent);
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider) {
		return getDetailsAwait(provider, false);
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, long timeout) {
		return getDetailsAwait(provider, false, OptionalLong.of(timeout));
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, boolean persistent) {
		return getDetailsAwait(provider, persistent, OptionalLong.empty());
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, boolean persistent, OptionalLong timeout) {
		try {
			CompletableFuture<WaveDetails> future = getDetails(provider, persistent, HIGH_PRIORITY);

			if (timeout.isPresent()) {
				return future.get(timeout.getAsLong(), TimeUnit.SECONDS);
			} else {
				return future.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (TimeoutException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public void getDetails(String file, Consumer<WaveDetails> consumer, boolean priority) {
		getDetails(new FileWaveDataProvider(file), consumer, priority);
	}

	public void getDetails(WaveDataProvider provider, Consumer<WaveDetails> consumer, boolean priority) {
		getDetails(provider, false, priority ? HIGH_PRIORITY : DEFAULT_PRIORITY).thenAccept(consumer);
	}

	/**
	 * Retrieve {@link WaveDetails} for the given {@link WaveDataProvider}, queueing its computation
	 * if it is not already available.
	 *
	 * @param provider  {@link WaveDataProvider} to retrieve details for.
	 * @param persistent  If true, the details will be stored in the library for future use
	 *                    even if no associated file can be found.
	 *
	 * @return  {@link CompletableFuture} with the {@link WaveDetails} for the given provider.
	 */
	protected CompletableFuture<WaveDetails> getDetails(WaveDataProvider provider, boolean persistent, double priority) {
		WaveDetails existing = info.get(provider.getIdentifier());

		if (!isComplete(existing)) {
			return submitJob(provider, persistent, priority).getFuture();
		} else {
			existing.setPersistent(persistent || existing.isPersistent());
			return CompletableFuture.completedFuture(existing);
		}
	}

	/**
	 * Compute {@link WaveDetails} for the given {@link WaveDataProvider}.
	 *
	 * @param provider  {@link WaveDataProvider} to retrieve details for.
	 * @param persistent  If true, the details will be stored in the library for future use
	 *                    even if no associated file can be found.
	 *
	 * @return  {@link WaveDetails} for the given provider, or null if an error occurs.
	 */
	protected WaveDetails computeDetails(WaveDataProvider provider, boolean persistent) {
		try {
			String id = provider.getIdentifier();

			WaveDetails details = info.computeIfAbsent(id, k -> computeDetails(provider, null, persistent));
			if (getWaveDetailsFactory().getFeatureProvider() != null && details.getFeatureData() == null) {
				details = computeDetails(provider, details, persistent);
				info.put(id, details);
			}

			details.setPersistent(persistent || details.isPersistent());
			return details;
		} catch (Exception e) {
			warn("Failed to create WaveDetails for " +
					provider.getKey() + " (" +
					Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()) + ")");
			if (!(e.getCause() instanceof IOException) || !(provider instanceof FileWaveDataProvider)) {
				if (getErrorListener() == null) {
					e.printStackTrace();
				} else {
					getErrorListener().accept(e);
				}
			}

			return null;
		}
	}

	public boolean isComplete(WaveDetails details) {
		return details != null &&
				details.getFreqData() != null &&
				details.getFeatureData() != null;
	}

	public Map<String, Double> getSimilarities(String key) {
		return getSimilarities(new FileWaveDataProvider(key));
	}

	public Map<String, Double> getSimilarities(WaveDetails details) {
		return computeSimilarities(details).getSimilarities();
	}

	public Map<String, Double> getSimilarities(WaveDataProvider provider) {
		return computeSimilarities(getDetailsAwait(provider, false)).getSimilarities();
	}

	public void resetSimilarities() {
		getAllDetails().forEach(d -> d.getSimilarities().clear());
		// log("Similarities reset");
	}

	protected WaveDetails processJob(WaveDetailsJob job) {
		if (job == null) return null;

		try {
			return computeDetails(job.getTarget(), job.isPersistent());
		} finally {
			reportProgress();
		}
	}

	protected WaveDetailsJob submitJob(WaveDataProvider provider, boolean persistent, double priority) {
		return submitJob(new WaveDetailsJob(this::processJob, provider, persistent, priority));
	}

	protected WaveDetailsJob submitJob(WaveDetailsJob job) {
		if (job.getTarget() != null) {
			identifiers.computeIfAbsent(job.getTarget().getKey(), k -> job.getTarget().getIdentifier());
		}

		executor.execute(job);
		totalJobs++;
		return job;
	}

	public double getProgress() {
		int totalJobs = getTotalJobs();
		int queueSize = getPendingJobs();

		double total = totalJobs <= 0 ? 1.0 : totalJobs;
		double remaining = queueSize / total;
		double progress = remaining <= 0.0 ? 1.0 : 1.0 - remaining;

		if (queueSize == 0 && progress != 1.0) {
			warn("Progress != 1.0 despite no jobs in progress (" +
					progress + ", " + totalJobs + " total)");
			progress = 1.0;
		}

		return progress;
	}

	protected void reportProgress() {
		if (progressListener == null) return;
		progressListener.accept(getProgress());
	}

	public void stop() { stop(5); }

	public void stop(int timeout) {
		try {
			queue.clear();
			executor.shutdown();

			if (!executor.awaitTermination(timeout, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			warn("Interrupted waiting for executor to shut down", e);
			Thread.currentThread().interrupt();
		} finally {
			executor = null;
		}
	}

	protected WaveDetails computeDetails(WaveDataProvider provider, WaveDetails existing, boolean persistent) {
		WaveDetails details = factory.forProvider(provider, existing);
		details.setPersistent((existing != null && existing.isPersistent()) || persistent);
		return details;
	}

	protected WaveDetails computeSimilarities(WaveDetails details) {
		try {
			allDetails()
					.filter(d -> details == null || !Objects.equals(d.getIdentifier(), details.getIdentifier()))
					.filter(d -> !details.getSimilarities().containsKey(d.getIdentifier()))
					.forEach(d -> {
						double similarity = factory.similarity(details, d);

						if (Math.abs(similarity - 1.0) < 1e-5) {
							warn("Identical features for distinct files");
						}

						details.getSimilarities().put(d.getIdentifier(), similarity);

						if (details.getIdentifier() != null)
							d.getSimilarities().put(details.getIdentifier(), similarity);
					});
		} catch (Exception e) {
			log("Failed to load similarities for " + details.getIdentifier() +
					" (" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()) + ")");
		}

		return details;
	}

	public CompletableFuture<Void> refresh() {
		try {
			CompletableFuture<Void> future = new CompletableFuture<>();
			AtomicBoolean submitted = new AtomicBoolean(false);

			root.children().forEach(f -> {
				FileWaveDataProvider provider = f.get();

				boolean skipped = true;

				try {
					if (provider == null || isComplete(info.get(provider.getIdentifier())))
						return;

					// Similarities may no longer be valid if the library is being updated
					skipped = false;
					if (!submitted.get()) resetSimilarities();

					submitJob(new WaveDetailsJob(this::processJob, provider, true, BACKGROUND_PRIORITY));
				} finally {
					// Make sure not to repeatedly reset the similarities
					if (!skipped) submitted.set(true);
				}
			});

			WaveDetailsJob last = new WaveDetailsJob(this::processJob, null, false, -1.0);
			last.getFuture().thenRun(() -> future.complete(null));
			executor.execute(last);
			return future;
		} finally {
			reportProgress();
		}
	}

	public void cleanup(Predicate<String> preserve) {
		// Identify current library files
		Set<String> activeIds = root.children()
				.map(Supplier::get).filter(Objects::nonNull)
				.map(WaveDataProvider::getIdentifier).filter(Objects::nonNull)
				.collect(Collectors.toSet());

		// Exclude persistent info and those associated with active files
		// or otherwise explicitly preserved
		List<String> keys = info.entrySet().stream()
				.filter(e -> !e.getValue().isPersistent())
				.filter(e -> !activeIds.contains(e.getKey()))
				.filter(e -> preserve == null || !preserve.test(e.getKey()))
				.map(Map.Entry::getKey).toList();

		// Remove everything else
		keys.forEach(info::remove);
	}
}
