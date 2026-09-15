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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that generates square wave audio with configurable frequency,
 * amplitude, phase, duty cycle, and envelope. Includes PolyBLEP anti-aliasing
 * to reduce aliasing artifacts at the wave transitions.
 * <p>
 * The square wave alternates between +1 and -1 based on the duty cycle, producing
 * a hollow, clarinet-like tone with only odd harmonics at 50% duty cycle.
 * Parameter storage, setup, the runtime setters and position advancement are
 * inherited from {@link OscillatorCell}; the duty cycle is the one parameter
 * this cell adds.
 *
 * <h2>PolyBLEP Anti-Aliasing</h2>
 * <p>The square wave has two sharp transitions per cycle (rising and falling edges) which
 * can cause aliasing. PolyBLEP is applied at both edges to reduce aliasing artifacts.</p>
 *
 * @see OscillatorCell
 * @see SquareWaveCellData
 */
public class SquareWaveCell extends OscillatorCell {
	/** Hardware-side data storage (PackedCollection) for all wave parameters including duty cycle. */
	private final SquareWaveCellData data;

	/** Initial duty cycle (0.0 to 1.0), copied to hardware memory during setup(). */
	private double initialDutyCycle;

	/**
	 * Creates a new SquareWaveCell with default polymorphic data storage.
	 */
	public SquareWaveCell() {
		this(new SquareWavePolymorphicData());
	}

	/**
	 * Creates a new SquareWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public SquareWaveCell(SquareWaveCellData data) {
		super(data);
		this.data = data;
		this.initialDutyCycle = 0.5;
	}

	/**
	 * Sets the initial duty cycle (pulse width). This value is only used during {@link #setup()}.
	 * <p>
	 * The duty cycle controls the ratio of high to low in each wave period.
	 * </p>
	 *
	 * @param dutyCycle ratio of high to low (0.0 to 1.0, default 0.5)
	 */
	public void setDutyCycle(double dutyCycle) {
		this.initialDutyCycle = dutyCycle;
	}

	/**
	 * Creates a compiled operation that updates the duty cycle in hardware memory.
	 * <p>
	 * Use this for pulse-width modulation (PWM) effects where the duty cycle
	 * changes dynamically during audio generation.
	 * </p>
	 *
	 * @param dutyCycle a Producer providing the duty cycle value (0.0 to 1.0)
	 * @return a Supplier that, when executed, updates the hardware-side duty cycle
	 */
	public Supplier<Runnable> setDutyCycle(Producer<PackedCollection> dutyCycle) {
		return a(data.getDutyCycle(), dutyCycle);
	}

	/**
	 * Creates a compiled operation that initializes all hardware memory with initial
	 * values, adding the duty cycle to the values initialized by {@link OscillatorCell}.
	 *
	 * @return a Supplier that, when executed, initializes hardware memory
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("SquareWaveCell Duty Cycle Assignment");
		setup.add(a(data.getDutyCycle(), c(initialDutyCycle)));
		setup.add(super.setup());
		return setup;
	}

	/**
	 * Computes +1 when the cycle position is below the duty cycle and -1 otherwise,
	 * with PolyBLEP anti-aliasing applied at both edges. The rising edge sits at
	 * cycle position 0, so its correction is evaluated at the cycle position directly;
	 * the falling edge sits at the duty cycle, so its correction is evaluated at the
	 * cycle position measured from the duty cycle, wrapped back into a single cycle.
	 *
	 * @param phase the raw phase in cycles
	 * @return the anti-aliased square waveform
	 */
	@Override
	protected CollectionProducer waveform(CollectionProducer phase) {
		CollectionProducer frac = cyclePosition(phase);
		Producer<PackedCollection> dutyCycleProd = data.getDutyCycle();
		Producer<PackedCollection> dt = data.getWaveLength();

		CollectionProducer rawSquare = lessThan(frac, dutyCycleProd, c(1.0), c(-1.0));
		CollectionProducer blepRising = polyBlep(frac, dt);

		CollectionProducer fracMinusDuty = subtract(frac, dutyCycleProd);
		CollectionProducer blepFallingArg = add(fracMinusDuty, lessThan(frac, dutyCycleProd, c(1.0), c(0.0)));
		CollectionProducer blepFalling = polyBlep(blepFallingArg, dt);

		return subtract(add(rawSquare, blepRising), blepFalling);
	}

	/**
	 * Internal data class that implements SquareWaveCellData using PolymorphicAudioData storage.
	 */
	private static class SquareWavePolymorphicData extends PolymorphicAudioData implements SquareWaveCellData {
		/** Creates a SquareWavePolymorphicData with default polymorphic storage. */
		public SquareWavePolymorphicData() {
			super();
		}
	}
}
