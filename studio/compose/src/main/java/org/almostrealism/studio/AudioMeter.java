/*
 * Copyright 2022 Michael Murray
 *
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

package org.almostrealism.studio;

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.studio.computations.ClipCounter;
import org.almostrealism.studio.computations.SilenceDurationComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Real-time audio metering component that monitors an audio stream for clipping and silence.
 * Implements {@link Receptor} to slot into an audio processing pipeline, optionally forwarding
 * the incoming audio to a downstream receptor while accumulating clip and silence statistics.
 *
 * <p>Clip detection counts samples that fall outside the configurable
 * [{@link #setClipMinValue min}, {@link #setClipMaxValue max}] range.
 * Silence detection accumulates consecutive samples below the configured silence threshold.</p>
 */
public class AudioMeter implements Receptor<PackedCollection>, Lifecycle, ScalarFeatures, PairFeatures {
	/** Optional downstream receptor to which incoming audio is forwarded; may be {@code null}. */
	private Receptor<PackedCollection> forwarding;

	/** Running count of samples that have exceeded the clip range. */
	private final PackedCollection clipCount = new PackedCollection(1);

	/** Clip detection range stored as a {@link Pair} of (min, max) values. */
	private final Pair clipSettings = new Pair(-1.0, 1.0);

	/** Amplitude threshold below which a sample is considered silence. */
	private final PackedCollection silenceValue = new PackedCollection(1);

	/** Accumulated count of consecutive silent samples. */
	private final PackedCollection silenceDuration = new PackedCollection(1);

	/** Registered listeners notified when audio data is pushed; currently unsupported. */
	private final List<Consumer<PackedCollection>> listeners;

	/** Creates a new {@code AudioMeter} with default clip range of [-1.0, 1.0] and no listeners. */
	public AudioMeter() {
		listeners = new ArrayList<>();
	}

	/**
	 * Sets the downstream receptor to which all incoming audio is forwarded.
	 *
	 * @param r the forwarding receptor, or {@code null} to disable forwarding
	 */
	public void setForwarding(Receptor<PackedCollection> r) { this.forwarding = r; }

	/**
	 * Returns the downstream receptor, or {@code null} if none is configured.
	 *
	 * @return the forwarding receptor
	 */
	public Receptor<PackedCollection> getForwarding() { return this.forwarding; }

	/**
	 * Sets the amplitude threshold below which a sample is counted as silence.
	 *
	 * @param value the silence threshold
	 */
	public void setSilenceValue(double value) { this.silenceValue.setMem(value); }

	/**
	 * Returns the accumulated count of consecutive silent samples since the last reset.
	 *
	 * @return the silence duration as a sample count
	 */
	public long getSilenceDuration() { return (long) this.silenceDuration.toDouble(); }

	/**
	 * Sets the minimum amplitude value for clip detection; samples below this are clipped.
	 *
	 * @param value the minimum non-clip amplitude
	 */
	public void setClipMinValue(double value) { this.clipSettings.setA(value); }

	/**
	 * Sets the maximum amplitude value for clip detection; samples above this are clipped.
	 *
	 * @param value the maximum non-clip amplitude
	 */
	public void setClipMaxValue(double value) { this.clipSettings.setB(value); }

	/**
	 * Returns the total count of clipped samples detected since the last reset.
	 *
	 * @return the clip count
	 */
	public long getClipCount() { return (long) clipCount.toDouble(); }

	/**
	 * Registers a listener to receive audio data notifications on each push.
	 * Note: listener notification is currently unsupported and will throw
	 * {@link UnsupportedOperationException} when audio is pushed.
	 *
	 * @param listener the listener to register
	 */
	public void addListener(Consumer<PackedCollection> listener) {
		listeners.add(listener);
	}

	/**
	 * Removes a previously registered audio data listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(Consumer<PackedCollection> listener) {
		listeners.remove(listener);
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		OperationList push = new OperationList("AudioMeter Push");
		push.add(new ClipCounter(() -> new Provider<>(clipCount), v(clipSettings), protein));
		push.add(new SilenceDurationComputation(() -> new Provider<>(silenceDuration), c(silenceValue), protein));
		if (forwarding != null)  push.add(forwarding.push(protein));

		if (!listeners.isEmpty()) {
			// push.add(() -> () -> listeners.forEach(listener -> listener.accept(p)));
			throw new UnsupportedOperationException();
		}

		return push;
	}

	@Override
	public void reset() {
		Lifecycle.super.reset();
		silenceDuration.setMem(0.0);
		silenceValue.setMem(0.0);
		clipCount.setMem(0.0);
	}
}
