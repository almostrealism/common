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

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
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

import java.util.List;

public class FourierTransform extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	public static boolean enableRecursion = true;
	public static boolean enableRelative = true;

	private int varIdx = 0;
	private boolean inverse;

	public FourierTransform(int bins, Producer<PackedCollection<?>> input) {
		this(1, bins, input);
	}

	public FourierTransform(int count, int bins, Producer<PackedCollection<?>> input) {
		this(count, bins, false, input);
	}

	public FourierTransform(int count, int bins, boolean inverse, Producer<PackedCollection<?>> input) {
		super(inverse ? "fourierTransformInverse"  : "fourierTransform",
				enableRelative ?
						new TraversalPolicy(count, 2, bins).traverse(1) :
						new TraversalPolicy(count, 2, bins),
				input);
		this.inverse = inverse;
	}

	@Override
	public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "FourierTransform"));

		int size = getShape().getSize();

		Expression outputPosition = kernel(context).multiply(e(size));

		ArrayVariable<Double> output = getArgument(0);
		ArrayVariable<Double> input = getArgument(1);

		Scope<?> calculateTransform = calculateTransform(outputPosition, size, getShape().getTotalSize());
		scope.getRequiredScopes().add(calculateTransform);
		scope.getStatements().add(calculateTransform.call(
				output.ref(outputPosition), input.ref(outputPosition),
				e(size / 2), inverse ? e(1) : e(0), e(0)));

		if (inverse) {
			for (int i = 0; i < size; i++) {
				Expression<?> arg = getArgument(0).valueAt(i);
				scope.getStatements().add(arg.assign(arg.divide(e(size / 2))));
			}
		}

		return scope;
	}

	@Override
	public FourierTransform generate(List<Process<?, ?>> children) {
		return new FourierTransform(getShape().length(0), getShape().length(2), inverse, (Producer) children.get(1));
	}

	protected ArrayVariable<Double> addParameter(Scope<?> method, String name, int size) {
		ArrayVariable<Double> source = new ArrayVariable<>(Double.class, name, e(size));
		method.getParameters().add(source);
		return source;
	}

	protected Scope<?> calculateTransform(Expression<Integer> outputPosition, int size, int totalSize) {
		OperationMetadata calculateTransformMetadata = new OperationMetadata
				(getFunctionName() + "_calculateTransform", "Calculate Transform");
		Scope<PackedCollection<?>> calculateTransform = new Scope<>(getFunctionName() + "_calculateTransform", calculateTransformMetadata);

		ArrayVariable<Double> output = addParameter(calculateTransform, "output", size);
		ArrayVariable<Double> input = addParameter(calculateTransform, "input", size);
		output.setSortHint(-1);

		Variable<Integer, ?> len = Variable.integer("len");
		Variable<Integer, ?> inverseTransform = Variable.integer("inverseTransform");
		Variable<Integer, ?> isFirstSplit = Variable.integer("isFirstSplit");

		calculateTransform.getParameters().add(len);
		calculateTransform.getParameters().add(inverseTransform);
		calculateTransform.getParameters().add(isFirstSplit);

		return populateCalculateTransform(calculateTransform, output, input,
							len.ref(), inverseTransform, isFirstSplit.ref(),
							outputPosition, size, totalSize);
	}

	protected Scope<?> populateCalculateTransform(Scope<?> calculateTransform,
												 ArrayVariable<Double> output, ArrayVariable<Double> input,
												 Expression<?> len, Variable<Integer, ?> inverseTransform,
												 Expression<?> isFirstSplit,
												 Expression<Integer> outputPosition,
												 int size, int totalSize) {
		ArrayVariable<Double> radix2 = size >= 2 ?
				calculateTransform.declareArray(getNameProvider(),"radix2_" + varIdx++, e(size / 2)) : null;
		ArrayVariable<Double> radix4Part1 =
				size >= 4 ?
						calculateTransform.declareArray(getNameProvider(), "radix4Part1_" + varIdx++, e(size / 4)) : null;
		ArrayVariable<Double> radix4Part2 =
				size >= 4 ?
				calculateTransform.declareArray(getNameProvider(), "radix4Part2_" + varIdx++, e(size / 4)) : null;
		ArrayVariable<Double> radix2FFT =
				size > 2 ?
				calculateTransform.declareArray(getNameProvider(), "radix2FFT_" + varIdx++, e(size / 2)) : null;
		ArrayVariable<Double> radix4Part1FFT =
				size >= 4 ?
				calculateTransform.declareArray(getNameProvider(), "radix4Part1FFT_" + varIdx++, e(size / 4)) : null;
		ArrayVariable<Double> radix4Part2FFT =
				size >= 4 ?
				calculateTransform.declareArray(getNameProvider(), "radix4Part2FFT_" + varIdx++, e(size / 4)) : null;

		Cases cases = new Cases<>(); {
			if (size >= 4) {
				Scope<?> four = cases.addCase(len.greaterThanOrEqual(e(4)), new Scope<>());
				{
					Expression halfN = four.declareInteger("halfN_" + varIdx++, len.divide(e(2)));
					Expression quarterN = four.declareInteger("quarterN_" + varIdx++, len.divide(e(4)));
					Expression tripleQuarterN = four.declareInteger("tripleQuarterN_" + varIdx++, quarterN.multiply(e(3)));

					Expression an = e(2).multiply(pi()).divide(len);
					Expression angle = four.declareDouble("angle_" + varIdx++, conditional(inverseTransform.ref().lessThanOrEqual(e(0)), an.minus(), an));
					Expression i = four.declareDouble("i_" + varIdx++, conditional(inverseTransform.ref().lessThanOrEqual(e(0)), e(1), e(-1)));

					Repeated loop = new Repeated<>();
					{
						InstanceReference k = Variable.integer("k" + varIdx++).ref();
						loop.setIndex(k.getReferent());
						loop.setCondition(k.lessThan(quarterN));
						loop.setInterval(e(1));

						Scope<?> body = new Scope<>();
						{
							Expression kPlusTripleQuarterN = body.declareInteger("kPlusTripleQuarterN_" + varIdx++, k.add(tripleQuarterN));
							Expression kPlusHalfN = body.declareInteger("kPlusHalfN_" + varIdx++, k.add(halfN));
							Expression kPlusQuarterN = body.declareInteger("kPlusQuarterN_" + varIdx++, k.add(quarterN));
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

							Expression imaginaryTimesSubR = body.declareDouble("imaginaryTimesSubR_" + varIdx++, i.multiply(bMinusD_i).minus());
							Expression imaginaryTimesSubI = body.declareDouble("imaginaryTimesSubI_" + varIdx++, i.multiply(bMinusD_r));

							Expression angleK = body.declareDouble("angleK_" + varIdx++, angle.multiply(k));
							Expression omegaR = body.declareDouble("omegaR_" + varIdx++, angleK.cos());
							Expression omegaI = body.declareDouble("omegaI_" + varIdx++, angleK.sin());

							Expression angleK3 = angleK.multiply(3);
							Expression omegaToPowerOf3R = body.declareDouble("omegaToPowerOf3R_" + varIdx++, angleK3.cos());
							Expression omegaToPowerOf3I = body.declareDouble("omegaToPowerOf3I_" + varIdx++, angleK3.sin());

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

					four.getChildren().add(
							recursion(calculateTransform, radix2, radix4Part1, radix4Part2,
									radix2FFT, radix4Part1FFT, radix4Part2FFT,
									halfN, quarterN, inverseTransform,
									outputPosition, size, totalSize));

					Repeated loop2 = new Repeated<>();
					{
						InstanceReference k = Variable.integer("k").ref();
						loop2.setIndex(k.getReferent());
						loop2.setCondition(k.lessThan(quarterN));
						loop2.setInterval(e(1));

						Scope<?> body = new Scope<>();
						{
							Expression doubleK = body.declareInteger("doubleK_" + varIdx++, k.multiply(2));
							Expression quadrupleK = body.declareInteger("quadrupleK_" + varIdx++, doubleK.multiply(2));

							Scope first = new Scope<>();
							{
								first.assign(output.valueAt(doubleK.multiply(2)), radix2FFT.valueAt(doubleK).divide(len));
								first.assign(output.valueAt(doubleK.multiply(2).add(1)), radix2FFT.valueAt(doubleK.add(1)).divide(len));
								first.assign(output.valueAt(quadrupleK.add(1).multiply(2)), radix4Part1FFT.valueAt(doubleK).divide(len));
								first.assign(output.valueAt(quadrupleK.add(1).multiply(2).add(1)), radix4Part1FFT.valueAt(doubleK.add(1)).divide(len));
								first.assign(output.valueAt(doubleK.add(halfN).multiply(2)), radix2FFT.valueAt(k.add(quarterN)).divide(len));
								first.assign(output.valueAt(doubleK.add(halfN).multiply(2).add(1)), radix2FFT.valueAt(k.add(quarterN).add(1)).divide(len));
								first.assign(output.valueAt(quadrupleK.add(3).multiply(2)), radix4Part2FFT.valueAt(doubleK).divide(len));
								first.assign(output.valueAt(quadrupleK.add(3).multiply(2).add(1)), radix4Part2FFT.valueAt(doubleK.add(1)).divide(len));
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

							body.addCase(inverseTransform.ref().greaterThan(e(0)).and(isFirstSplit.greaterThan(e(0))), first, alt);

							loop2.add(body);
						}

						four.getChildren().add(loop2);
					}
				}
			}

			if (size >= 2) {
				Scope<?> two = cases.addCase(len.greaterThanOrEqual(e(2)), new Scope<>());
				{
					if (enableRecursion) {
						Scope calculateRadix2 = radix2(outputPosition, size, totalSize);
						calculateTransform.getRequiredScopes().add(calculateRadix2);
						two.getStatements().add(
								calculateRadix2.call(output.ref(), input.ref(),
										len, inverseTransform.ref(), isFirstSplit));
					} else {
						populateRadix2(two,
										output, input, len,
										inverseTransform, isFirstSplit, size);
					}
				}
			}

			Scope last = cases.addCase(null, new Scope<>());
			{
				InstanceReference i = Variable.integer("i").ref();
				Repeated inOutLoop = new Repeated<>(i.getReferent(), (i.lessThan(len.multiply(2))));
				Scope<?> inOut = new Scope<>(); {
					inOut.assign(output.valueAt(i), input.valueAt(i));
					inOutLoop.add(inOut);
				}

				last.add(inOutLoop);
			}

			if (size < 2) {
				calculateTransform.add(last);
			} else {
				calculateTransform.add(cases);
			}
		}

		return calculateTransform;
	}

	protected Scope recursion(Scope<?> calculateTransform,
								 ArrayVariable<Double> radix2,
								 ArrayVariable<Double> radix4Part1, ArrayVariable<Double> radix4Part2,
								 ArrayVariable<Double> radix2FFT,
								 ArrayVariable<Double> radix4Part1FFT, ArrayVariable<Double> radix4Part2FFT,
								 Expression<?> halfN, Expression<?> quarterN,
								 Variable<Integer, ?> inverseTransform,
								 Expression<Integer> outputPosition,
								 int size, int totalSize) {

		Scope recursion = new Scope();

		if (enableRecursion) {
			recursion.getStatements().add(calculateTransform
					.call(radix2FFT.ref(), radix2.ref(),
							halfN, inverseTransform.ref(), e(0)));

			recursion.getStatements().add(calculateTransform
					.call(radix4Part1FFT.ref(), radix4Part1.ref(),
							quarterN, inverseTransform.ref(), e(0)));

			recursion.getStatements().add(calculateTransform
					.call(radix4Part2FFT.ref(), radix4Part2.ref(),
							quarterN, inverseTransform.ref(), e(0)));
		} else if (size >= 4) {
			recursion.getChildren().add(
					populateCalculateTransform(new Scope<>(), radix2FFT, radix2,
							halfN, inverseTransform, e(0),
							outputPosition, size / 2, totalSize));

			recursion.getChildren().add(
					populateCalculateTransform(new Scope<>(), radix4Part1FFT, radix4Part1,
							quarterN, inverseTransform, e(0),
							outputPosition, size / 4, totalSize));

			recursion.getChildren().add(
					populateCalculateTransform(new Scope<>(), radix4Part2FFT, radix4Part2,
							quarterN, inverseTransform, e(0),
							outputPosition, size / 4, totalSize));
		}

		return recursion;
	}


	protected Scope<?> radix2(Expression<Integer> outputPosition, int size, int totalSize) {
		OperationMetadata radix2Metadata = new OperationMetadata
				(getFunctionName() + "_radix2", "Radix 2");
		Scope<PackedCollection<?>> radix2 = new Scope<>(getFunctionName() + "_radix2", radix2Metadata);

		ArrayVariable<Double> output = addParameter(radix2, "output", size);
		ArrayVariable<Double> input = addParameter(radix2, "input", size);
		output.setSortHint(-1);

		Variable<Integer, ?> len = Variable.integer("len");
		Variable<Integer, ?> inverseTransform = Variable.integer("inverseTransform");
		Variable<Integer, ?> isFirstSplit = Variable.integer("isFirstSplit");

		radix2.getParameters().add(len);
		radix2.getParameters().add(inverseTransform);
		radix2.getParameters().add(isFirstSplit);

		return populateRadix2(radix2, output, input, len.ref(), inverseTransform, isFirstSplit.ref(), size);
	}

	protected <T> Scope<T> populateRadix2(Scope<T> radix2, ArrayVariable<Double> output, ArrayVariable<Double> input,
							  Expression<?> len, Variable<Integer, ?> inverseTransform,
							  Expression<?> isFirstSplit, int size) {

		ArrayVariable<Double> even = radix2.declareArray(getNameProvider(), "even_" + varIdx++, e(size / 2));
		ArrayVariable<Double> odd = radix2.declareArray(getNameProvider(), "odd_" + varIdx++, e(size / 2));
		ArrayVariable<Double> evenFft = radix2.declareArray(getNameProvider(), "evenFft_" + varIdx++, e(size / 2));
		ArrayVariable<Double> oddFft = radix2.declareArray(getNameProvider(), "oddFft_" + varIdx++, e(size / 2));

		Cases cases = new Cases<>(); {
			Scope<?> main = cases.addCase(len.greaterThanOrEqual(e(2)), new Scope<>(), null);
			Expression halfN = main.declareInteger("halfN_" + varIdx++, len.divide(e(2)));
			Expression angle = main.declareDouble("angle_" + varIdx++, e(2).multiply(pi()).divide(len));
			main.addCase(inverseTransform.ref().eq(e(0)), assign(angle, angle.minus()));

			Repeated evenOdd = new Repeated<>(); {
				InstanceReference k = Variable.integer("k").ref();
				evenOdd.setIndex(k.getReferent());
				evenOdd.setCondition(k.lessThan(halfN));
				evenOdd.setInterval(e(1));

				Scope<?> body = new Scope(); {
					Expression<?> kPlusHalfN = body.declareInteger("kPlusHalfN_" + varIdx++, k.add(halfN));
					Expression<?> angleK = body.declareDouble("angleK_" + varIdx++, k.multiply(k));
					Expression<?> omegaR = body.declareDouble("omegaR_" + varIdx++, angleK.cos());
					Expression<?> omegaI = body.declareDouble("omegaI_" + varIdx++, angleK.sin());

					Expression k2 = k.multiply(2);
					Expression kPlusHalfN2 = kPlusHalfN.multiply(2);

					body.assign(even.valueAt(k2), input.valueAt(k2).add(input.valueAt(kPlusHalfN2)));
					body.assign(even.valueAt(k2.add(1)), input.valueAt(k2.add(1)).add(input.valueAt(kPlusHalfN2.add(1))));

					Expression inKMinusInKPlusHalfNr = body.declareDouble("inKMinusInKPlusHalfNr_" + varIdx++, input.valueAt(k2).subtract(input.valueAt(kPlusHalfN2)));
					Expression inKMinusInKPlusHalfNi = body.declareDouble("inKMinusInKPlusHalfNi_" + varIdx++, input.valueAt(k2.add(1)).subtract(input.valueAt(kPlusHalfN2.add(1))));

					Expression oddExp[] = complexProduct(inKMinusInKPlusHalfNr, inKMinusInKPlusHalfNi, omegaR, omegaI);
					body.assign(odd.valueAt(k2), oddExp[0]);
					body.assign(odd.valueAt(k2.add(1)), oddExp[1]);

					evenOdd.add(body);
				}

				main.add(evenOdd);
			}

			main.getChildren().add(recursionRadix2(radix2, evenFft, even, oddFft, odd, halfN, inverseTransform, size));

			Repeated loop2 = new Repeated<>(); {
				InstanceReference k = Variable.integer("k" + varIdx++).ref();
				loop2.setIndex(k.getReferent());
				loop2.setCondition(k.lessThan(halfN));
				loop2.setInterval(e(1));

				Scope<?> body = new Scope(); {
					Expression k2 = k.multiply(2);
					Expression doubleK = body.declareInteger("doubleK_" + varIdx++, k.multiply(2));

					Scope first = new Scope<>(); {
						first.assign(output.valueAt(doubleK.multiply(2)),
										evenFft.valueAt(k2).divide(len));
						first.assign(output.valueAt(doubleK.multiply(2).add(1)),
										evenFft.valueAt(k2.add(1)).divide(len));
						first.assign(output.valueAt(doubleK.add(1).multiply(2)),
										oddFft.valueAt(k2).divide(len));
						first.assign(output.valueAt(doubleK.add(1).multiply(2).add(1)),
										oddFft.valueAt(k2.add(1)).divide(len));
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
							.and(isFirstSplit.greaterThan(e(0))),
							first, alt);

					loop2.add(body);
				}

				main.add(loop2);
			}

			Scope<?> base = cases.addCase(null, new Scope<>(), null); {
				InstanceReference i = Variable.integer("i" + varIdx++).ref();
				Repeated inOutLoop = new Repeated<>(i.getReferent(), (i.lessThan(len.multiply(2))));
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

	protected Scope recursionRadix2(Scope<?> radix2,
									ArrayVariable<Double> evenFft, ArrayVariable<Double> even,
									ArrayVariable<Double> oddFft, ArrayVariable<Double> odd,
									Expression<?> halfN,
									Variable<Integer, ?> inverseTransform,
									int size) {
		Scope recursion = new Scope();

		if (enableRecursion) {
			Method<?> evenFftCall = radix2.call(evenFft.ref(), even.ref(), halfN, inverseTransform.ref(), e(0));
			recursion.getStatements().add(evenFftCall);

			Method<?> oddFftCall = radix2.call(oddFft.ref(), odd.ref(), halfN, inverseTransform.ref(), e(0));
			recursion.getStatements().add(oddFftCall);
		} else if (size >= 4) {
			recursion.getChildren().add(
					populateRadix2(new Scope<>(), evenFft, even, halfN, inverseTransform, e(0), size / 2));

			recursion.getChildren().add(
					populateRadix2(new Scope<>(), oddFft, odd, halfN, inverseTransform, e(0), size / 2));
		}

		return recursion;
	}
}
