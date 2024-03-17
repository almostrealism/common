/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.stats.DistributionFeatures;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

public class AutoregressiveModel implements DistributionFeatures, CodeFeatures {
	private final IntConsumer step;
	private final IntConsumer token;
	private final Supplier<PackedCollection<?>> logits;
	private final int vocabSize;

	private Evaluable<PackedCollection<?>> indexOfMax;
	private Evaluable<PackedCollection<?>> rescale;
	private Evaluable<? extends PackedCollection<?>> softmax;

	private int currentStep;
	private int currentToken;

	private PackedCollection<?> temperature;
	private int[] prompt;
	private int promptTokens;

	public AutoregressiveModel(IntConsumer step, IntConsumer token, Supplier<PackedCollection<?>> logits, int vocabSize) {
		this.step = step;
		this.token = token;
		this.logits = logits;
		this.vocabSize = vocabSize;
		this.temperature = new PackedCollection<>(1);

		this.indexOfMax = indexOfMax(x(vocabSize)).get();
		this.rescale = (Evaluable) x(vocabSize).divide(cp(temperature)).get();
		this.softmax = Process.optimized(softmax(x(vocabSize))).get();
	}

	public void setCurrentToken(int currentToken) {
		this.currentToken = currentToken;
	}

	public int getCurrentToken() {
		return currentToken;
	}

	public double getTemperature() {
		return temperature.toDouble(0);
	}

	public void setTemperature(double temperature) {
		this.temperature.set(0, temperature);
	}

	public void setPrompt(int promptTokens[], int length) {
		this.prompt = promptTokens;
		this.promptTokens = length;
	}

	public int next() {
		step.accept(currentStep);
		token.accept(currentToken);

		PackedCollection<?> logit = logits.get();

		if (currentStep < promptTokens) {
			currentToken = prompt[currentStep];
		} else if (temperature.toDouble(0) == 0.0) {
			currentToken = (int) indexOfMax.evaluate(logit).toDouble(0);
		} else {
			rescale.into(logit).evaluate(logit);
			softmax.into(logit).evaluate(logit);
			currentToken = sample(logit, vocabSize);
		}

		currentStep++;
		return currentToken;
	}
}
