/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.computations;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.audio.filter.ADSREnvelopeData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

/**
 * Advances an ADSR envelope by one sample, computing the next
 * {@code [phase, position, currentLevel]} runtime state entirely on the device.
 *
 * <p>The branching ADSR state machine — attack, decay, sustain and release phases with
 * piecewise-linear level segments and position-triggered transitions — is emitted as a
 * {@link HybridScope} of ternary {@code conditional} expressions rather than composed from
 * comparison-producer nodes. Expressing the selection at the {@link Expression} level keeps the
 * shared operands (the current phase and position) and the phase constants as ordinary subtrees, so
 * the whole update compiles to a single operation with no per-value kernel and no argument-matching
 * ambiguity between value-equal constants.</p>
 *
 * <p>The transition point is expressed with a {@code >=} comparison and immediate (zero-time)
 * transitions fall out of the ternary: when a phase time is not positive the position advance is not
 * evaluated and the position clamps to one, matching the host recurrence
 * {@code ADSREnvelope} previously ran per sample.</p>
 *
 * @see ADSREnvelopeData#tickUpdate(int)
 */
public class ADSREnvelopeComputation extends CollectionProducerComputationBase
		implements ProducerComputation<PackedCollection> {

	/** Sample rate in Hz; an invariant used to derive the per-sample time step. */
	private final int sampleRate;

	/**
	 * Creates the per-sample ADSR update.
	 *
	 * @param sampleRate   sample rate in Hz
	 * @param phase        the current phase producer (a {@code (1)} slot)
	 * @param position     the current position producer (a {@code (1)} slot)
	 * @param level        the current level producer (a {@code (1)} slot)
	 * @param attackTime   the attack time producer in seconds (a {@code (1)} slot)
	 * @param decayTime    the decay time producer in seconds (a {@code (1)} slot)
	 * @param sustainLevel the sustain level producer (a {@code (1)} slot)
	 * @param releaseTime  the release time producer in seconds (a {@code (1)} slot)
	 * @param releaseLevel the release-start level producer (a {@code (1)} slot)
	 */
	public ADSREnvelopeComputation(int sampleRate,
								   Producer<PackedCollection> phase,
								   Producer<PackedCollection> position,
								   Producer<PackedCollection> level,
								   Producer<PackedCollection> attackTime,
								   Producer<PackedCollection> decayTime,
								   Producer<PackedCollection> sustainLevel,
								   Producer<PackedCollection> releaseTime,
								   Producer<PackedCollection> releaseLevel) {
		super(null, new TraversalPolicy(3),
				phase, position, level, attackTime, decayTime, sustainLevel, releaseTime, releaseLevel);
		this.sampleRate = sampleRate;
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection> scope = new HybridScope<>(this);

		double dt = 1.0 / sampleRate;

		Expression<Double> phase = getArgument(1).valueAt(0);
		Expression<Double> position = getArgument(2).valueAt(0);
		Expression<Double> level = getArgument(3).valueAt(0);
		Expression<Double> attackTime = getArgument(4).valueAt(0);
		Expression<Double> decayTime = getArgument(5).valueAt(0);
		Expression<Double> sustainLevel = getArgument(6).valueAt(0);
		Expression<Double> releaseTime = getArgument(7).valueAt(0);
		Expression<Double> releaseLevel = getArgument(8).valueAt(0);

		Expression attackPos = advance(position, attackTime, dt);
		Expression decayPos = advance(position, decayTime, dt);
		Expression releasePos = advance(position, releaseTime, dt);

		Expression isAttack = phase.eq(e((double) ADSREnvelopeData.PHASE_ATTACK));
		Expression isDecay = phase.eq(e((double) ADSREnvelopeData.PHASE_DECAY));
		Expression isSustain = phase.eq(e((double) ADSREnvelopeData.PHASE_SUSTAIN));
		Expression isRelease = phase.eq(e((double) ADSREnvelopeData.PHASE_RELEASE));

		Expression newPhase = conditional(isAttack,
				conditional(attackPos.greaterThanOrEqual(e(1.0)),
						e((double) ADSREnvelopeData.PHASE_DECAY), e((double) ADSREnvelopeData.PHASE_ATTACK)),
				conditional(isDecay,
						conditional(decayPos.greaterThanOrEqual(e(1.0)),
								e((double) ADSREnvelopeData.PHASE_SUSTAIN), e((double) ADSREnvelopeData.PHASE_DECAY)),
						conditional(isSustain, e((double) ADSREnvelopeData.PHASE_SUSTAIN),
								conditional(isRelease,
										conditional(releasePos.greaterThanOrEqual(e(1.0)),
												e((double) ADSREnvelopeData.PHASE_IDLE),
												e((double) ADSREnvelopeData.PHASE_RELEASE)),
										phase))));

		Expression newPosition = conditional(isAttack,
				conditional(attackPos.greaterThanOrEqual(e(1.0)), e(0.0), attackPos),
				conditional(isDecay,
						conditional(decayPos.greaterThanOrEqual(e(1.0)), e(0.0), decayPos),
						conditional(isSustain, position,
								conditional(isRelease,
										conditional(releasePos.greaterThanOrEqual(e(1.0)), e(0.0), releasePos),
										position))));

		Expression attackLevel = clampToOne(attackPos);
		Expression decayLevel = e(1.0).subtract(e(1.0).subtract(sustainLevel).multiply(clampToOne(decayPos)));
		Expression releaseLevelOut = releaseLevel.multiply(e(1.0).subtract(clampToOne(releasePos)));

		Expression newLevel = conditional(isAttack, attackLevel,
				conditional(isDecay, decayLevel,
						conditional(isSustain, sustainLevel,
								conditional(isRelease, releaseLevelOut, level))));

		ArrayVariable output = (ArrayVariable) getOutputVariable();
		scope.assign(output.valueAt(0), newPhase);
		scope.assign(output.valueAt(1), newPosition);
		scope.assign(output.valueAt(2), newLevel);

		return scope;
	}

	/**
	 * Advances a phase position by {@code dt / time}, or clamps to one when {@code time} is not
	 * positive (an immediate transition). The division is only reached when {@code time} is positive,
	 * so the zero-time case never divides by zero.
	 *
	 * @param position the current position expression
	 * @param time     the phase duration expression in seconds
	 * @param dt       the per-sample time step ({@code 1 / sampleRate})
	 * @return the advanced position expression
	 */
	private Expression advance(Expression<Double> position, Expression<Double> time, double dt) {
		return conditional(time.greaterThan(e(0.0)),
				position.add(e(dt).divide(time)), e(1.0));
	}

	/**
	 * Clamps an expression to a maximum of one, the device equivalent of {@code min(1, x)}.
	 *
	 * @param x the expression to clamp
	 * @return {@code min(1, x)}
	 */
	private Expression clampToOne(Expression x) {
		return conditional(x.greaterThan(e(1.0)), e(1.0), x);
	}
}
