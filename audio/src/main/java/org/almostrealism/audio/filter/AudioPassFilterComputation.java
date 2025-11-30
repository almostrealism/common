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

package org.almostrealism.audio.filter;

import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.AudioFilterData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;

public class AudioPassFilterComputation extends OperationComputationAdapter<PackedCollection> implements CodeFeatures {
	public static double MAX_INPUT = 0.99;

	private final boolean high;

	public AudioPassFilterComputation(AudioFilterData data, Producer<PackedCollection> frequency, Producer<PackedCollection> resonance, Producer<PackedCollection> input, boolean high) {
		super(data.getOutput(),
				frequency,
				resonance,
				data.getSampleRate(),
				data.getC(),
				data.getA1(),
				data.getA2(),
				data.getA3(),
				data.getB1(),
				data.getB2(),
				data.getInputHistory0(),
				data.getInputHistory1(),
				data.getOutputHistory0(),
				data.getOutputHistory1(),
				data.getOutputHistory2(),
				input);
		this.high = high;
	}

	private AudioPassFilterComputation(boolean high, Producer... arguments) {
		super(arguments);
		this.high = high;
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new AudioPassFilterComputation(high, children.toArray(Producer[]::new));
	}

	public ArrayVariable<Double> getOutput() { return getArgument(0); }
	public ArrayVariable<Double> getFrequency() { return getArgument(1); }
	public ArrayVariable<Double> getResonance() { return getArgument(2); }
	public ArrayVariable<Double> getSampleRate() { return getArgument(3); }
	public ArrayVariable<Double> getC() { return getArgument(4); }
	public ArrayVariable<Double> getA1() { return getArgument(5); }
	public ArrayVariable<Double> getA2() { return getArgument(6); }
	public ArrayVariable<Double> getA3() { return getArgument(7); }
	public ArrayVariable<Double> getB1() { return getArgument(8); }
	public ArrayVariable<Double> getB2() { return getArgument(9); }
	public ArrayVariable<Double> getInputHistory0() { return getArgument(10); }
	public ArrayVariable<Double> getInputHistory1() { return getArgument(11); }
	public ArrayVariable<Double> getOutputHistory0() { return getArgument(12); }
	public ArrayVariable<Double> getOutputHistory1() { return getArgument(13); }
	public ArrayVariable<Double> getOutputHistory2() { return getArgument(14); }
	public ArrayVariable<Double> getInput() { return getArgument(15); }

	protected Expression<Double> output() { return getOutput().valueAt(0); }
	protected Expression<Double> frequency() { return getFrequency().getValueRelative(0); }
	protected Expression<Double> resonance() { return getResonance().getValueRelative(0); }
	protected Expression<Double> sampleRate() { return getSampleRate().getValueRelative(0); }
	protected Expression<Double> c() { return getC().valueAt(0); }
	protected Expression<Double> a1() { return getA1().valueAt(0); }
	protected Expression<Double> a2() { return getA2().valueAt(0); }
	protected Expression<Double> a3() { return getA3().valueAt(0); }
	protected Expression<Double> b1() { return getB1().valueAt(0); }
	protected Expression<Double> b2() { return getB2().valueAt(0); }
	protected Expression<Double> inputHistory0() { return getInputHistory0().valueAt(0); }
	protected Expression<Double> inputHistory1() { return getInputHistory1().valueAt(0); }
	protected Expression<Double> outputHistory0() { return getOutputHistory0().valueAt(0); }
	protected Expression<Double> outputHistory1() { return getOutputHistory1().valueAt(0); }
	protected Expression<Double> outputHistory2() { return getOutputHistory2().valueAt(0); }
	protected Expression<Double> input() { return getInput().valueAt(0); }

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		purgeVariables();

		Expression<Double> one = e(1.0);
		Expression<Double> pi = e(Math.PI);

		if (high) {
			addVariable(getC().reference(e(0)).assign(pi.multiply(frequency()).divide(sampleRate()).tan()));
			addVariable(getA1().reference(e(0)).assign(one.divide(one.add(resonance().multiply(c())).add(c().multiply(c())))));
			addVariable(getA2().reference(e(0)).assign(e(-2.0).multiply(a1())));
			addVariable(getA3().reference(e(0)).assign(a1()));
			addVariable(getB1().reference(e(0)).assign(e(2.0).multiply(c().multiply(c()).subtract(one)).multiply(a1())));
			addVariable(getB2().reference(e(0)).assign(one.subtract(resonance().multiply(c())).add(c().multiply(c())).multiply(a1())));
		} else {
			addVariable(getC().reference(e(0)).assign(one.divide(pi.multiply(frequency()).divide(sampleRate()).tan())));
			addVariable(getA1().reference(e(0)).assign(one.divide(one.add(resonance().multiply(c())).add(c().multiply(c())))));
			addVariable(getA2().reference(e(0)).assign(e(2.0).multiply(a1())));
			addVariable(getA3().reference(e(0)).assign(getA1().valueAt(0)));
			addVariable(getB1().reference(e(0)).assign(e(2.0).multiply(one.subtract(c().multiply(c()))).multiply(a1())));
			addVariable(getB2().reference(e(0)).assign(one.subtract(resonance().multiply(c())).add(c().multiply(c())).multiply(a1())));
		}

		Expression<Double> input = Max.of(Min.of(getInput().valueAt(0), e(MAX_INPUT)), e(-MAX_INPUT));

		addVariable(getOutput().reference(e(0)).assign(
				a1().multiply(input).add(a2().multiply(inputHistory0())).add(a3().multiply(inputHistory1())).subtract(
						b1().multiply(outputHistory0())).subtract(b2().multiply(outputHistory1()))));
		addVariable(getInputHistory1().reference(e(0)).assign(getInputHistory0().valueAt(0)));
		addVariable(getInputHistory0().reference(e(0)).assign(input));
		addVariable(getOutputHistory2().reference(e(0)).assign(getOutputHistory1().valueAt(0)));
		addVariable(getOutputHistory1().reference(e(0)).assign(getOutputHistory0().valueAt(0)));
		addVariable(getOutputHistory0().reference(e(0)).assign(getOutput().valueAt(0)));
	}
}
