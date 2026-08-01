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

package org.almostrealism.ml.dsl;

/**
 * The whole-bank value produced by a {@code [channel]} subscript during vectorized
 * {@code for each channel} interpretation: instead of one channel's row, the subscript
 * yields the complete per-channel collection (a coefficient bank, gain vector, or state
 * buffer set) wrapped in this marker type.
 *
 * <p>Wrapping matters for safety: a primitive that has not been taught the bank form
 * cannot accidentally treat the full bank as a single channel's data — any attempt to
 * convert this wrapper through the standard argument paths fails, which makes the
 * interpreter fall back to per-channel dispatch with unchanged semantics. Bank-aware
 * primitives ({@code fir}, {@code scale}, {@code delay}) unwrap {@link #getSource()}
 * explicitly and apply the whole bank in one computation.</p>
 *
 * @see PdslInterpreter#enableVectorizedForEach
 *
 * @author  Michael Murray
 */
public class PdslChannelBank {

	/** The unwrapped per-channel collection or producer. */
	private final Object source;

	/** Number of channels the source spans. */
	private final int channels;

	/**
	 * Wraps a per-channel argument source.
	 *
	 * @param source   the collection or producer holding one entry (or row) per channel
	 * @param channels the channel count
	 */
	public PdslChannelBank(Object source, int channels) {
		this.source = source;
		this.channels = channels;
	}

	/**
	 * Returns the unwrapped per-channel collection or producer.
	 *
	 * @return the bank source
	 */
	public Object getSource() { return source; }

	/**
	 * Returns the number of channels the source spans.
	 *
	 * @return the channel count
	 */
	public int getChannels() { return channels; }

	@Override
	public String toString() {
		return "PdslChannelBank[" + channels + " channels]";
	}
}
