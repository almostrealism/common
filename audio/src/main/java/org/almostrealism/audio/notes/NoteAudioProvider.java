/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.notes;

import io.almostrealism.code.CacheManager;
import io.almostrealism.code.CachedValue;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Validity;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.DelegateWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.SupplierWaveDataProvider;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.io.Console;
import org.almostrealism.util.KeyUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NoteAudioProvider implements NoteAudio, Validity, Comparable<NoteAudioProvider>, SamplingFeatures {
	public static boolean enableVerbose = false;

	private static final CacheManager<PackedCollection> audioCache = new CacheManager<>();

	static {
		OperationList accessListener = new OperationList();
		accessListener.add(() -> CacheManager.maxCachedEntries(audioCache, 200));
		accessListener.add(() -> () -> {
			if (Math.random() < 0.005) {
				long size = audioCache.getCachedOrdered().stream()
						.map(CachedValue::evaluate)
						.map(PackedCollection::getMem)
						.mapToLong(m -> m instanceof RAM ? ((RAM) m).getSize() : 0)
						.sum();
				if (enableVerbose && size > 1024)
					CellFeatures.console.features(NoteAudioProvider.class).log("Cache size = " + (size / 1024 / 1024) + "mb");
			}
		});

		audioCache.setAccessListener(accessListener.get());
		audioCache.setValid(c -> !c.isDestroyed());
		audioCache.setClear(c -> {
			// If the cached value is a subset of another value,
			// it does not make sense to destroy it just because
			// it is no longer being stored in the cache
			if (c.getRootDelegate().getMemLength() == c.getMemLength()) {
				c.destroy();
			}
		});
	}

	private WaveDataProvider provider;

	private KeyboardTuning tuning;
	private KeyPosition<?> root;
	private Double bpm;
	private final int sampleRate;

	private final Map<NoteAudioKey, Producer<PackedCollection>> notes;

	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root) {
		this(provider, root, null);
	}

	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root, Double bpm) {
		this(provider, root, bpm, OutputLine.sampleRate, null);
	}

	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root,
							 Double bpm, int sampleRate, KeyboardTuning tuning) {
		this.provider = provider;
		this.tuning = tuning;
		setRoot(root);
		setBpm(bpm);
		this.sampleRate = sampleRate;
		this.notes = new HashMap<>();
	}

	public WaveDataProvider getProvider() { return provider; }
	public void setProvider(WaveDataProvider provider) { this.provider = provider; }

	public KeyPosition<?> getRoot() { return root; }
	public void setRoot(KeyPosition<?> root) { this.root = root; }

	public Double getBpm() { return bpm; }
	public void setBpm(Double bpm) { this.bpm = bpm; }

	@Override
	public void setTuning(KeyboardTuning tuning) {
		if (tuning != this.tuning) {
			this.tuning = tuning;
			notes.clear();
		}
	}

	public KeyboardTuning getTuning() { return tuning; }

	@Override
	public int getSampleRate() { return sampleRate; }

	@Override
	public double getDuration(KeyPosition<?> target) {
		if (target == null) return provider.getDuration();

		double r = tuning.getTone(target).asHertz() / tuning.getTone(getRoot()).asHertz();
		return provider.getDuration(r);
	}

	public TraversalPolicy getShape(NoteAudioKey key) {
		double r = tuning.getTone(key.getPosition()).asHertz() / tuning.getTone(getRoot()).asHertz();
		return new TraversalPolicy(provider.getCount(r, sampleRate)).traverse(1);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel) {
		if (target == null) {
			target = getRoot();
		}

		NoteAudioKey key = new NoteAudioKey(target, channel);

		if (!notes.containsKey(key)) {
			notes.put(key, c(getShape(key), audioCache.get(computeAudio(key).get())));
		}

		return notes.get(key);
	}

	protected Producer<PackedCollection> computeAudio(NoteAudioKey key) {
		return () -> args -> {
			double r = key.getPosition() == null ? 1.0 :
					(tuning.getTone(key.getPosition()).asHertz() / tuning.getTone(getRoot()).asHertz());
			return provider.getChannelData(key.getAudioChannel(), r, sampleRate);
		};
	}

	public WaveData getWaveData() {
		return provider == null ? null : provider.get();
	}

	@Override
	public boolean isValid() {
		Boolean valid;

		try {
			valid = provider.getSampleRate() > 0;
		} catch (Exception e) {
			if (provider instanceof FileWaveDataProvider) {
				warn(e.getMessage() + "(" + ((FileWaveDataProvider) provider).getResourcePath() + ")");
			} else {
				warn(e.getMessage());
			}

			valid = false;
		}

		return valid;
	}

	public List<NoteAudioProvider> split(double durationBeats) {
		if (getBpm() == null)
			throw new IllegalArgumentException();

		double duration = durationBeats * 60.0 / getBpm();
		int frames = (int) (duration * sampleRate);
		int total = (int) (getProvider().getCount() / (duration * sampleRate));
		return IntStream.range(0, total)
				.mapToObj(i -> new DelegateWaveDataProvider(getProvider(), i * frames, frames))
				.map(p -> new NoteAudioProvider(p, getRoot(), getBpm(), sampleRate, tuning))
				.collect(Collectors.toList());
	}

	@Override
	public int compareTo(NoteAudioProvider o) {
		return getProvider().compareTo(o.getProvider());
	}

	@Override
	public Console console() {
		return CellFeatures.console;
	}

	public static NoteAudioProvider create(String source) {
		return create(source, WesternChromatic.C1);
	}

	public static NoteAudioProvider create(String source, KeyPosition root) {
		return new NoteAudioProvider(new FileWaveDataProvider(source), root);
	}

	public static NoteAudioProvider create(String source, KeyPosition root, KeyboardTuning tuning) {
		return new NoteAudioProvider(new FileWaveDataProvider(source), root, null, OutputLine.sampleRate, tuning);
	}

	public static NoteAudioProvider create(Supplier<PackedCollection> audioSupplier) {
		return create(audioSupplier, WesternChromatic.C1);
	}

	public static NoteAudioProvider create(Supplier<PackedCollection> audioSupplier, KeyPosition root) {
		return new NoteAudioProvider(new SupplierWaveDataProvider(KeyUtils.generateKey(), audioSupplier, OutputLine.sampleRate), root);
	}
}
