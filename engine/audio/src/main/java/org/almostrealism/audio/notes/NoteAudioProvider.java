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

/**
 * Primary implementation of {@link NoteAudio} that provides pitch-shifted audio
 * from a {@link WaveDataProvider}. Manages caching of resampled audio data for
 * different pitches and channels, automatically adjusting playback rate based on
 * the frequency ratio between the root note and target note. Supports BPM-based
 * splitting for rhythm-aligned audio segments and provides factory methods for
 * creating instances from files or suppliers.
 *
 * @see NoteAudio
 * @see WaveDataProvider
 * @see KeyboardTuning
 */
public class NoteAudioProvider implements NoteAudio, Validity, Comparable<NoteAudioProvider>, SamplingFeatures {
	/** When true, logs audio cache size statistics to the console. */
	public static boolean enableVerbose = false;

	/** LRU cache for resampled audio data, bounded to 200 entries and cleared on low-memory conditions. */
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

	/** Source of raw audio data; may be a file, memory buffer, or any other provider. */
	private WaveDataProvider provider;

	/** Keyboard tuning used to compute frequency ratios for pitch shifting. */
	private KeyboardTuning tuning;

	/** The key position of the source audio at its natural (unshifted) pitch. */
	private KeyPosition<?> root;

	/** Source audio tempo in beats per minute, used for BPM-aligned splitting; null if not applicable. */
	private Double bpm;

	/** Output sample rate in Hz; resampled data is produced at this rate. */
	private final int sampleRate;

	/** Cache mapping (target pitch, channel) pairs to their resampled audio producers. */
	private final Map<NoteAudioKey, Producer<PackedCollection>> notes;

	/**
	 * Creates a NoteAudioProvider at the default sample rate with no BPM or tuning.
	 *
	 * @param provider source of audio data
	 * @param root     key position of the audio at its natural pitch
	 */
	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root) {
		this(provider, root, null);
	}

	/**
	 * Creates a NoteAudioProvider at the default sample rate with the given BPM.
	 *
	 * @param provider source of audio data
	 * @param root     key position of the audio at its natural pitch
	 * @param bpm      source tempo in BPM for beat-aligned splitting, or null
	 */
	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root, Double bpm) {
		this(provider, root, bpm, OutputLine.sampleRate, null);
	}

	/**
	 * Creates a NoteAudioProvider with full control over all parameters.
	 *
	 * @param provider   source of audio data
	 * @param root       key position of the audio at its natural pitch
	 * @param bpm        source tempo in BPM for beat-aligned splitting, or null
	 * @param sampleRate output sample rate in Hz
	 * @param tuning     keyboard tuning for frequency ratio computation, or null for default
	 */
	public NoteAudioProvider(WaveDataProvider provider, KeyPosition<?> root,
							 Double bpm, int sampleRate, KeyboardTuning tuning) {
		this.provider = provider;
		this.tuning = tuning;
		setRoot(root);
		setBpm(bpm);
		this.sampleRate = sampleRate;
		this.notes = new HashMap<>();
	}

	/** Returns the audio data provider. */
	public WaveDataProvider getProvider() { return provider; }

	/**
	 * Replaces the audio data provider.
	 *
	 * @param provider new provider to use
	 */
	public void setProvider(WaveDataProvider provider) { this.provider = provider; }

	/** Returns the key position corresponding to the audio at its natural (unshifted) pitch. */
	public KeyPosition<?> getRoot() { return root; }

	/**
	 * Sets the key position of the audio at its natural pitch.
	 *
	 * @param root the root key position
	 */
	public void setRoot(KeyPosition<?> root) { this.root = root; }

	/** Returns the source tempo in BPM, or null if not configured. */
	public Double getBpm() { return bpm; }

	/**
	 * Sets the source tempo in BPM used for beat-aligned splitting.
	 *
	 * @param bpm tempo in beats per minute, or null to disable
	 */
	public void setBpm(Double bpm) { this.bpm = bpm; }

	@Override
	public void setTuning(KeyboardTuning tuning) {
		if (tuning != this.tuning) {
			this.tuning = tuning;
			notes.clear();
		}
	}

	/** Returns the keyboard tuning system used for frequency ratio computation. */
	public KeyboardTuning getTuning() { return tuning; }

	@Override
	public int getSampleRate() { return sampleRate; }

	@Override
	public double getDuration(KeyPosition<?> target) {
		if (target == null) return provider.getDuration();

		double r = tuning.getTone(target).asHertz() / tuning.getTone(getRoot()).asHertz();
		return provider.getDuration(r);
	}

	/**
	 * Returns the shape (frame count) of the resampled audio for the given note key.
	 *
	 * @param key the (target pitch, channel) key
	 * @return the traversal policy describing the shape of the audio data
	 */
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

	/**
	 * Returns a Producer that evaluates to the resampled audio for the given note key.
	 *
	 * @param key the (target pitch, channel) key identifying the audio to compute
	 * @return a lazy producer that performs pitch-shifted resampling on evaluation
	 */
	protected Producer<PackedCollection> computeAudio(NoteAudioKey key) {
		return () -> args -> {
			double r = key.getPosition() == null ? 1.0 :
					(tuning.getTone(key.getPosition()).asHertz() / tuning.getTone(getRoot()).asHertz());
			return provider.getChannelData(key.getAudioChannel(), r, sampleRate);
		};
	}

	/**
	 * Returns the raw WaveData from the underlying provider at its native sample rate.
	 *
	 * @return WaveData, or null if no provider is set
	 */
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

	/**
	 * Splits this provider into sequential segments of the specified beat duration.
	 *
	 * <p>Requires {@link #getBpm()} to be set. The audio is divided into fixed-length
	 * segments aligned to beat boundaries, each returned as an independent NoteAudioProvider.</p>
	 *
	 * @param durationBeats length of each segment in beats
	 * @return list of NoteAudioProvider instances for each segment in order
	 * @throws IllegalArgumentException if BPM has not been set
	 */
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

	/**
	 * Creates a NoteAudioProvider for a file at the default root note (C1).
	 *
	 * @param source path to the audio file
	 * @return a new NoteAudioProvider
	 */
	public static NoteAudioProvider create(String source) {
		return create(source, WesternChromatic.C1);
	}

	/**
	 * Creates a NoteAudioProvider for a file at the specified root note.
	 *
	 * @param source path to the audio file
	 * @param root   key position of the audio at its natural pitch
	 * @return a new NoteAudioProvider
	 */
	public static NoteAudioProvider create(String source, KeyPosition root) {
		return new NoteAudioProvider(new FileWaveDataProvider(source), root);
	}

	/**
	 * Creates a NoteAudioProvider for a file with a specific root note and tuning system.
	 *
	 * @param source path to the audio file
	 * @param root   key position of the audio at its natural pitch
	 * @param tuning keyboard tuning for frequency ratio computation
	 * @return a new NoteAudioProvider
	 */
	public static NoteAudioProvider create(String source, KeyPosition root, KeyboardTuning tuning) {
		return new NoteAudioProvider(new FileWaveDataProvider(source), root, null, OutputLine.sampleRate, tuning);
	}

	/**
	 * Creates a NoteAudioProvider from an audio supplier at the default root note (C1).
	 *
	 * @param audioSupplier supplier that returns a PackedCollection of audio samples
	 * @return a new NoteAudioProvider
	 */
	public static NoteAudioProvider create(Supplier<PackedCollection> audioSupplier) {
		return create(audioSupplier, WesternChromatic.C1);
	}

	/**
	 * Creates a NoteAudioProvider from an audio supplier at the specified root note.
	 *
	 * @param audioSupplier supplier that returns a PackedCollection of audio samples
	 * @param root          key position of the audio at its natural pitch
	 * @return a new NoteAudioProvider
	 */
	public static NoteAudioProvider create(Supplier<PackedCollection> audioSupplier, KeyPosition root) {
		return new NoteAudioProvider(new SupplierWaveDataProvider(KeyUtils.generateKey(), audioSupplier, OutputLine.sampleRate), root);
	}
}
