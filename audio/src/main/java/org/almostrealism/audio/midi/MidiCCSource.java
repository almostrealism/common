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

package org.almostrealism.audio.midi;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.synth.ModulationSource;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A {@link ModulationSource} that receives values from MIDI CC messages.
 * <p>
 * MidiCCSource converts MIDI CC values (0-127) to a normalized range
 * suitable for modulation. It supports:
 * <ul>
 *   <li>Configurable output range (min/max)</li>
 *   <li>Various curve types (linear, exponential, S-curve)</li>
 *   <li>Optional value smoothing for noisy controllers</li>
 *   <li>Bipolar mode for center-detent controls</li>
 * </ul>
 * <p>
 * The source is thread-safe, using atomic operations for value updates.
 *
 * @see ModulationSource
 * @see CCMappingManager
 */
public class MidiCCSource implements ModulationSource, CodeFeatures {

	/**
	 * Curve types for CC value scaling.
	 */
	public enum CurveType {
		LINEAR,
		EXPONENTIAL,
		LOGARITHMIC,
		S_CURVE
	}

	private final int ccNumber;
	private final AtomicInteger rawValue;
	private final PackedCollection output;
	private volatile double smoothedValue;
	private double minValue;
	private double maxValue;
	private CurveType curve;
	private double smoothing;
	private boolean bipolar;

	/**
	 * Creates a CC source for the specified controller number.
	 *
	 * @param ccNumber the MIDI CC number (0-127)
	 */
	public MidiCCSource(int ccNumber) {
		this.ccNumber = ccNumber;
		this.rawValue = new AtomicInteger(0);
		this.output = new PackedCollection(1);
		this.smoothedValue = 0.0;
		this.minValue = 0.0;
		this.maxValue = 1.0;
		this.curve = CurveType.LINEAR;
		this.smoothing = 0.0;
		this.bipolar = false;
	}

	/**
	 * Returns the CC number this source responds to.
	 */
	public int getCCNumber() {
		return ccNumber;
	}

	/**
	 * Sets the raw MIDI CC value (0-127).
	 * Called by {@link CCMappingManager} when a CC message is received.
	 *
	 * @param midiValue the CC value (0-127)
	 */
	public void setValue(int midiValue) {
		rawValue.set(Math.max(0, Math.min(127, midiValue)));
	}

	/**
	 * Returns the raw MIDI CC value.
	 */
	public int getRawValue() {
		return rawValue.get();
	}

	/**
	 * Sets the output value range.
	 *
	 * @param min minimum output value
	 * @param max maximum output value
	 */
	public void setRange(double min, double max) {
		this.minValue = min;
		this.maxValue = max;
	}

	/**
	 * Returns the minimum output value.
	 */
	public double getMinValue() {
		return minValue;
	}

	/**
	 * Returns the maximum output value.
	 */
	public double getMaxValue() {
		return maxValue;
	}

	/**
	 * Sets the curve type for value scaling.
	 *
	 * @param curve the curve type
	 */
	public void setCurve(CurveType curve) {
		this.curve = curve != null ? curve : CurveType.LINEAR;
	}

	/**
	 * Returns the current curve type.
	 */
	public CurveType getCurve() {
		return curve;
	}

	/**
	 * Sets the smoothing factor.
	 * Higher values mean slower response to changes.
	 *
	 * @param smoothing smoothing factor (0.0 = no smoothing, 0.99 = very smooth)
	 */
	public void setSmoothing(double smoothing) {
		this.smoothing = Math.max(0.0, Math.min(0.99, smoothing));
	}

	/**
	 * Returns the smoothing factor.
	 */
	public double getSmoothing() {
		return smoothing;
	}

	/**
	 * Enables bipolar mode where 64 is center (outputs 0), and
	 * values below/above map to negative/positive.
	 *
	 * @param bipolar true for bipolar mode
	 */
	public void setBipolar(boolean bipolar) {
		this.bipolar = bipolar;
	}

	/**
	 * Returns true if in bipolar mode.
	 */
	public boolean isBipolar() {
		return bipolar;
	}

	// ========== ModulationSource Implementation ==========

	@Override
	public double getValue() {
		double normalized = normalizeValue(rawValue.get());
		double curved = applyCurve(normalized);
		double ranged = applyRange(curved);

		// Apply smoothing
		if (smoothing > 0) {
			smoothedValue = smoothedValue * smoothing + ranged * (1.0 - smoothing);
			output.setMem(0, smoothedValue);
			return smoothedValue;
		}

		output.setMem(0, ranged);
		return ranged;
	}

	@Override
	public Producer<PackedCollection> getOutput() {
		return p(output);
	}

	@Override
	public Supplier<Runnable> tick() {
		// No per-tick processing needed - values update on CC receipt
		return new OperationList("MidiCCSource Tick");
	}

	// ========== Internal Methods ==========

	/**
	 * Normalizes MIDI value (0-127) to 0.0-1.0 range.
	 * For bipolar mode, maps 0-127 to -1.0 to +1.0.
	 */
	private double normalizeValue(int midiValue) {
		if (bipolar) {
			// 0 = -1.0, 64 = 0.0, 127 = +1.0
			return (midiValue - 64) / 63.0;
		} else {
			return midiValue / 127.0;
		}
	}

	/**
	 * Applies the selected curve to the normalized value.
	 */
	private double applyCurve(double value) {
		switch (curve) {
			case EXPONENTIAL:
				if (bipolar) {
					double sign = Math.signum(value);
					return sign * value * value;
				}
				return value * value;

			case LOGARITHMIC:
				if (bipolar) {
					double sign = Math.signum(value);
					return sign * Math.sqrt(Math.abs(value));
				}
				return Math.sqrt(value);

			case S_CURVE:
				// Attempt a smoother S-curve using sine
				return 0.5 * (1.0 + Math.sin(Math.PI * (value - 0.5)));

			case LINEAR:
			default:
				return value;
		}
	}

	/**
	 * Maps the curved value to the output range.
	 */
	private double applyRange(double value) {
		if (bipolar) {
			// Map -1 to +1 onto minValue to maxValue
			double center = (minValue + maxValue) / 2.0;
			double halfRange = (maxValue - minValue) / 2.0;
			return center + value * halfRange;
		} else {
			return minValue + value * (maxValue - minValue);
		}
	}
}
