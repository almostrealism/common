/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time.computations;

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

public class FourierTransform extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	public FourierTransform(int bins, Producer<PackedCollection<?>> input) {
		super(null, new TraversalPolicy(bins, 2), input);
	}

	@Override
	public Scope<PackedCollection<?>> getScope() {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "FourierTransform"));

		int size = getShape().getTotalSize();

		Scope<?> calculateTransform = calculateTransform(size);
		scope.getRequiredScopes().add(calculateTransform);
		scope.getStatements().add(calculateTransform.call(
				getArgument(0, e(size)).ref(),
				getArgument(1, e(size)).ref(),
				e(size / 2), e(0), e(0)));
		return scope;
	}

	protected Scope<?> calculateTransform(int size) {
		ArrayVariable<Double> input = new ArrayVariable<>(this, Double.class, "input", e(size));
		ArrayVariable<Double> output = new ArrayVariable<>(this, Double.class, "output", e(size));
		output.setSortHint(-1);

		Variable<Integer, ?> len = Variable.integer("len");
		Variable<Integer, ?> inverseTransform = Variable.integer("inverseTransform");
		Variable<Integer, ?> isFirstSplit = Variable.integer("isFirstSplit");

		OperationMetadata calculateTransformMetadata = new OperationMetadata
				(getFunctionName() + "_calculateTransform", "Calculate Transform");
		Scope<PackedCollection<?>> calculateTransform = new Scope<>(getFunctionName() + "_calculateTransform", calculateTransformMetadata);
		calculateTransform.getParameters().add(output);
		calculateTransform.getParameters().add(input);
		calculateTransform.getParameters().add(len);
		calculateTransform.getParameters().add(inverseTransform);
		calculateTransform.getParameters().add(isFirstSplit);

		ArrayVariable<Double> radix2 = calculateTransform.declareArray(this, "radix2", e(size / 2));
		ArrayVariable<Double> radix4Part1 = calculateTransform.declareArray(this, "radix4Part1", e(size / 4));
		ArrayVariable<Double> radix4Part2 = calculateTransform.declareArray(this, "radix4Part2", e(size / 4));
		ArrayVariable<Double> radix2FFT = calculateTransform.declareArray(this, "radix2FFT", e(size / 2));
		ArrayVariable<Double> radix4Part1FFT = calculateTransform.declareArray(this, "radix4Part1FFT", e(size / 4));
		ArrayVariable<Double> radix4Part2FFT = calculateTransform.declareArray(this, "radix4Part2FFT", e(size / 4));

		Cases cases = new Cases<>(); {
			Scope<?> four = cases.addCase(len.ref().greaterThanOrEqual(e(4)), new Scope<>());
			{
				Expression halfN = four.declareInteger("halfN", len.ref().divide(e(2)));
				Expression quarterN = four.declareInteger("quarterN", len.ref().divide(e(4)));
				Expression tripleQuarterN = four.declareInteger("tripleQuarterN", quarterN.multiply(e(3)));

				Expression an = e(2).multiply(pi()).divide(len.ref());
				Expression angle = four.declareDouble("angle", conditional(inverseTransform.ref().lessThanOrEqual(e(0)), an.minus(), an));
				Expression i = four.declareDouble("i", conditional(inverseTransform.ref().lessThanOrEqual(e(0)), e(1), e(-1)));

				Repeated loop = new Repeated<>();
				{
					InstanceReference k = Variable.integer("k").ref();
					loop.setIndex(k.getReferent());
					loop.setCondition(k.lessThan(quarterN));
					loop.setInterval(e(1));

					Scope<?> body = new Scope<>();
					{
						Expression kPlusTripleQuarterN = body.declareInteger("kPlusTripleQuarterN", k.add(tripleQuarterN));
						Expression kPlusHalfN = body.declareInteger("kPlusHalfN", k.add(halfN));
						Expression kPlusQuarterN = body.declareInteger("kPlusQuarterN", k.add(quarterN));
						Expression k2 = k.multiply(2);
						Expression kPlusQuarterN2 = kPlusQuarterN.multiply(2);
						Expression kPlusHalfN2 = kPlusHalfN.multiply(2);
						Expression kPlusTripleQuarterN2 = kPlusTripleQuarterN.multiply(2);

						Expression ar = input.valueAt(k2);
						Expression ai = input.valueAt(k2.add(1));
						Expression br = input.valueAt(kPlusQuarterN2);
						Expression bi = input.valueAt(kPlusQuarterN2.add(1));
						Expression cr = input.valueAt(kPlusHalfN2);
						Expression ci = input.valueAt(kPlusHalfN2.add(1));
						Expression dr = input.valueAt(kPlusTripleQuarterN2);
						Expression di = input.valueAt(kPlusTripleQuarterN2.add(1));

						Expression arPlusCr = ar.add(cr);
						Expression aiPlusCi = ai.add(ci);
						Expression brPlusDr = br.add(dr);
						Expression biPlusDi = bi.add(di);

						body.assign(radix2.valueAt(k2), arPlusCr);
						body.assign(radix2.valueAt(k2.add(1)), aiPlusCi);
						body.assign(radix2.valueAt(kPlusQuarterN2), brPlusDr);
						body.assign(radix2.valueAt(kPlusQuarterN2.add(1)), biPlusDi);

						Expression bMinusD_r = br.subtract(dr);
						Expression bMinusD_i = bi.subtract(di);
						Expression aMinusC_r = ar.subtract(cr);
						Expression aMinusC_i = ai.subtract(ci);

						Expression imaginaryTimesSubR = body.declareDouble("imaginaryTimesSubR", i.multiply(bMinusD_i).minus());
						Expression imaginaryTimesSubI = body.declareDouble("imaginaryTimesSubI", i.multiply(bMinusD_r));

						Expression angleK = body.declareDouble("angleK", angle.multiply(k));
						Expression omegaR = body.declareDouble("omegaR", angleK.cos());
						Expression omegaI = body.declareDouble("omegaI", angleK.sin());

						Expression angleK3 = angleK.multiply(3);
						Expression omegaToPowerOf3R = body.declareDouble("omegaToPowerOf3R", angleK3.cos());
						Expression omegaToPowerOf3I = body.declareDouble("omegaToPowerOf3I", angleK3.sin());

						Expression aMinusCMinusItsR = aMinusC_r.subtract(imaginaryTimesSubR);
						Expression aMinusCMinusItsI = aMinusC_i.subtract(imaginaryTimesSubI);
						Expression aMinusCPlusItsR = aMinusC_r.add(imaginaryTimesSubR);
						Expression aMinusCPlusItsI = aMinusC_i.add(imaginaryTimesSubI);

						Expression radix4Part1Exp[] = complexProduct(aMinusCMinusItsR, aMinusCMinusItsI, omegaR, omegaI);
						body.assign(radix4Part1.valueAt(k2), radix4Part1Exp[0]);
						body.assign(radix4Part1.valueAt(k2.add(1)), radix4Part1Exp[1]);

						Expression radix4Part2Exp[] = complexProduct(aMinusCPlusItsR, aMinusCPlusItsI, omegaToPowerOf3R, omegaToPowerOf3I);
						body.assign(radix4Part2.valueAt(k2), radix4Part2Exp[0]);
						body.assign(radix4Part2.valueAt(k2.add(1)), radix4Part2Exp[1]);

						loop.add(body);
					}

					four.getChildren().add(loop);
				}

				Scope recursion = new Scope();
				{
					recursion.getStatements().add(calculateTransform.call(radix2FFT.ref(), radix2.ref(), halfN, inverseTransform.ref(), e(0)));
					recursion.getStatements().add(calculateTransform.call(radix4Part1FFT.ref(), radix4Part1.ref(), quarterN, inverseTransform.ref(), e(0)));
					recursion.getStatements().add(calculateTransform.call(radix4Part2FFT.ref(), radix4Part2.ref(), quarterN, inverseTransform.ref(), e(0)));

					four.getChildren().add(recursion);
				}

				Repeated loop2 = new Repeated<>();
				{
					InstanceReference k = Variable.integer("k").ref();
					loop2.setIndex(k.getReferent());
					loop2.setCondition(k.lessThan(quarterN));
					loop2.setInterval(e(1));

					Scope<?> body = new Scope<>();
					{
						Expression doubleK = body.declareInteger("doubleK", k.multiply(2));
						Expression quadrupleK = body.declareInteger("quadrupleK", doubleK.multiply(2));

						Scope first = new Scope<>();
						{
							first.assign(output.valueAt(doubleK.multiply(2)), radix2FFT.valueAt(doubleK).divide(len.ref()));
							first.assign(output.valueAt(doubleK.multiply(2).add(1)), radix2FFT.valueAt(doubleK.add(1)).divide(len.ref()));
							first.assign(output.valueAt(quadrupleK.add(1).multiply(2)), radix4Part1FFT.valueAt(doubleK).divide(len.ref()));
							first.assign(output.valueAt(quadrupleK.add(1).multiply(2).add(1)), radix4Part1FFT.valueAt(doubleK.add(1)).divide(len.ref()));
							first.assign(output.valueAt(doubleK.add(halfN).multiply(2)), radix2FFT.valueAt(k.add(quarterN)).divide(len.ref()));
							first.assign(output.valueAt(doubleK.add(halfN).multiply(2).add(1)), radix2FFT.valueAt(k.add(quarterN).add(1)).divide(len.ref()));
							first.assign(output.valueAt(quadrupleK.add(3).multiply(2)), radix4Part2FFT.valueAt(doubleK).divide(len.ref()));
							first.assign(output.valueAt(quadrupleK.add(3).multiply(2).add(1)), radix4Part2FFT.valueAt(doubleK.add(1)).divide(len.ref()));
						}

						Scope alt = new Scope<>();
						{
							alt.assign(output.valueAt(doubleK.multiply(2)), radix2FFT.valueAt(doubleK));
							alt.assign(output.valueAt(doubleK.multiply(2).add(1)), radix2FFT.valueAt(doubleK.add(1)));
							alt.assign(output.valueAt(quadrupleK.add(1).multiply(2)), radix4Part1FFT.valueAt(doubleK));
							alt.assign(output.valueAt(quadrupleK.add(1).multiply(2).add(1)), radix4Part1FFT.valueAt(doubleK.add(1)));
							alt.assign(output.valueAt(doubleK.add(halfN).multiply(2)), radix2FFT.valueAt(k.add(quarterN).multiply(2)));
							alt.assign(output.valueAt(doubleK.add(halfN).multiply(2).add(1)), radix2FFT.valueAt(k.add(quarterN).multiply(2).add(1)));
							alt.assign(output.valueAt(quadrupleK.add(3).multiply(2)), radix4Part2FFT.valueAt(doubleK));
							alt.assign(output.valueAt(quadrupleK.add(3).multiply(2).add(1)), radix4Part2FFT.valueAt(doubleK.add(1)));
						}

						body.addCase(inverseTransform.ref().greaterThan(e(0)).and(isFirstSplit.ref().greaterThan(e(0))), first, alt);

						loop2.add(body);
					}

					four.getChildren().add(loop2);
				}
			}

			Scope<?> two = cases.addCase(len.ref().greaterThanOrEqual(e(2)), new Scope<>());
			{
				Scope calculateRadix2 = radix2(size);
				calculateTransform.getRequiredScopes().add(calculateRadix2);
				two.getStatements().add(
						calculateRadix2.call(output.ref(), input.ref(),
							len.ref(), inverseTransform.ref(), isFirstSplit.ref()));
			}

			Scope<?> last = cases.addCase(null, new Scope<>());
			{
				InstanceReference i = Variable.integer("i").ref();
				Repeated inOutLoop = new Repeated<>(i.getReferent(), (i.lessThan(len.ref().multiply(2))));
				Scope<?> inOut = new Scope<>(); {
					inOut.assign(output.valueAt(i), input.valueAt(i));
					inOutLoop.add(inOut);
				}

				last.add(inOutLoop);
			}

			calculateTransform.add(cases);
		}

		return calculateTransform;
	}

	protected Scope<?> radix2(int size) {
		ArrayVariable<Double> input = new ArrayVariable<>(this, Double.class, "input", e(size));
		ArrayVariable<Double> output = new ArrayVariable<>(this, Double.class, "output", e(size));
		output.setSortHint(-1);

		Variable<Integer, ?> len = Variable.integer("len");
		Variable<Integer, ?> inverseTransform = Variable.integer("inverseTransform");
		Variable<Integer, ?> isFirstSplit = Variable.integer("isFirstSplit");

		OperationMetadata radix2Metadata = new OperationMetadata
				(getFunctionName() + "_radix2", "Radix 2");
		Scope<PackedCollection<?>> radix2 = new Scope<>(getFunctionName() + "_radix2", radix2Metadata);
		radix2.getParameters().add(output);
		radix2.getParameters().add(input);
		radix2.getParameters().add(len);
		radix2.getParameters().add(inverseTransform);
		radix2.getParameters().add(isFirstSplit);

		ArrayVariable<Double> even = radix2.declareArray(this, "even", e(size / 2));
		ArrayVariable<Double> odd = radix2.declareArray(this, "odd", e(size / 2));
		ArrayVariable<Double> evenFft = radix2.declareArray(this, "evenFft", e(size / 2));
		ArrayVariable<Double> oddFft = radix2.declareArray(this, "oddFft", e(size / 2));

		Cases cases = new Cases<>(); {
			Scope<?> main = cases.addCase(len.ref().greaterThanOrEqual(e(2)), new Scope<>(), null);
			Expression halfN = main.declareInteger("halfN", len.ref().divide(e(2)));
			Expression angle = main.declareDouble("angle", e(2).multiply(pi()).divide(len.ref()));
			main.addCase(inverseTransform.ref().eq(e(0)), assign(angle, angle.minus()));

			Repeated evenOdd = new Repeated<>(); {
				InstanceReference k = Variable.integer("k").ref();
				evenOdd.setIndex(k.getReferent());
				evenOdd.setCondition(k.lessThan(halfN));
				evenOdd.setInterval(e(1));

				Scope<?> body = new Scope(); {
					Expression<?> kPlusHalfN = body.declareInteger("kPlusHalfN", k.add(halfN));
					Expression<?> angleK = body.declareDouble("angleK", k.multiply(k));
					Expression<?> omegaR = body.declareDouble("omegaR", angleK.cos());
					Expression<?> omegaI = body.declareDouble("omegaI", angleK.sin());

					Expression k2 = k.multiply(2);
					Expression kPlusHalfN2 = kPlusHalfN.multiply(2);

					body.assign(even.valueAt(k2), input.valueAt(k2).add(input.valueAt(kPlusHalfN2)));
					body.assign(even.valueAt(k2.add(1)), input.valueAt(k2.add(1)).add(input.valueAt(kPlusHalfN2.add(1))));

					Expression inKMinusInKPlusHalfNr = body.declareDouble("inKMinusInKPlusHalfNr", input.valueAt(k2).subtract(input.valueAt(kPlusHalfN2)));
					Expression inKMinusInKPlusHalfNi = body.declareDouble("inKMinusInKPlusHalfNi", input.valueAt(k2.add(1)).subtract(input.valueAt(kPlusHalfN2.add(1))));

					Expression oddExp[] = complexProduct(inKMinusInKPlusHalfNr, inKMinusInKPlusHalfNi, omegaR, omegaI);
					body.assign(odd.valueAt(k2), oddExp[0]);
					body.assign(odd.valueAt(k2.add(1)), oddExp[1]);

					evenOdd.add(body);
				}

				main.add(evenOdd);
			}

			Scope recursion = new Scope(); {
				Method<?> evenFftCall = radix2.call(evenFft.ref(), even.ref(), halfN, inverseTransform.ref(), e(0));
				recursion.getStatements().add(evenFftCall);

				Method<?> oddFftCall = radix2.call(oddFft.ref(), odd.ref(), halfN, inverseTransform.ref(), e(0));
				recursion.getStatements().add(oddFftCall);

				main.getChildren().add(recursion);
			}

			Repeated loop2 = new Repeated<>(); {
				InstanceReference k = Variable.integer("k").ref();
				loop2.setIndex(k.getReferent());
				loop2.setCondition(k.lessThan(halfN));
				loop2.setInterval(e(1));

				Scope<?> body = new Scope(); {
					Expression k2 = k.multiply(2);
					Expression doubleK = body.declareInteger("doubleK", k.multiply(2));

					Scope first = new Scope<>(); {
						first.assign(output.valueAt(doubleK.multiply(2)),
										evenFft.valueAt(k2).divide(len.ref()));
						first.assign(output.valueAt(doubleK.multiply(2).add(1)),
										evenFft.valueAt(k2.add(1)).divide(len.ref()));
						first.assign(output.valueAt(doubleK.add(1).multiply(2)),
										oddFft.valueAt(k2).divide(len.ref()));
						first.assign(output.valueAt(doubleK.add(1).multiply(2).add(1)),
										oddFft.valueAt(k2.add(1)).divide(len.ref()));
					}

					Scope alt = new Scope<>(); {
						alt.assign(output.valueAt(doubleK.multiply(2)),
								evenFft.valueAt(k2));
						alt.assign(output.valueAt(doubleK.multiply(2).add(1)),
								evenFft.valueAt(k2.add(1)));
						alt.assign(output.valueAt(doubleK.add(1).multiply(2)),
								oddFft.valueAt(k2));
						alt.assign(output.valueAt(doubleK.add(1).multiply(2).add(1)),
								oddFft.valueAt(k2.add(1)));
					}

					body.addCase(inverseTransform.ref().greaterThan(e(0))
							.and(isFirstSplit.ref().greaterThan(e(0))),
							first, alt);

					loop2.add(body);
				}

				main.add(loop2);
			}

			Scope<?> base = cases.addCase(null, new Scope<>(), null); {
				InstanceReference i = Variable.integer("i").ref();
				Repeated inOutLoop = new Repeated<>(i.getReferent(), (i.lessThan(len.ref().multiply(2))));
				Scope<?> inOut = new Scope<>(); {
					inOut.assign(output.valueAt(i), input.valueAt(i));
					inOutLoop.add(inOut);
				}

				base.add(inOutLoop);
			}

			radix2.add(cases);
		}

		return radix2;
	}
}
