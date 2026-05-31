/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.ml.unet.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.DiffusionFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.AdamOptimizer;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.texture.GraphicsConverter;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/** Tests the UNet model architecture for diffusion-based image generation. */
public class UNetTest extends TestSuiteBase implements AttentionFeatures, DiffusionFeatures, RGBFeatures {

	/** Batch size for model input. */
	int batchSize = 1;

	/** Number of input channels. */
	int channels = 1;

	/** Dimensionality factors for UNet decoder stages. */
	int[] dimFactors = { 1, 2, 4 };
	// int dimFactors[] = { 1, 2, 4, 8 };

	/** Number of diffusion timesteps. */
	int timesteps = 300;

	/** Linear beta schedule for diffusion process. */
	CollectionProducer betas = linearBetaSchedule(timesteps);

	/** Alpha values computed as 1.0 minus beta. */
	CollectionProducer alphas = c(1.0).subtract(betas);

	/** Cumulative product of alpha values. */
	CollectionProducer alphasCumProd = cumulativeProduct(alphas, false);

	/** Cumulative product of alpha values with preceding timestep. */
	CollectionProducer alphasCumProdPrev = cumulativeProduct(alphas, true);

	/** Reciprocal of alpha square root. */
	CollectionProducer sqrtRecipAlphas = sqrt(alphas.reciprocal());

	/** Square root of cumulative alpha product. */
	PackedCollection sqrtAlphasCumProd = sqrt(alphasCumProd).evaluate();

	/** Square root of one minus cumulative alpha product. */
	PackedCollection sqrtOneMinusAlphasCumProd = sqrt(c(1.0).subtract(alphasCumProd)).evaluate();

	/** Posterior variance for diffusion reverse process. */
	CollectionProducer posteriorVariance = betas
			.multiply(c(1.0).subtract(alphasCumProdPrev))
			.divide(c(1.0).subtract(alphasCumProd));


	/** Creates a ResNet block with default group count of 8. */
	protected Block block(int dim, int dimOut, int rows, int cols) {
		return block(dim, dimOut, 8, rows, cols, null);
	}

	/** Creates a ResNet block with specified group count and default no scale/shift. */
	protected Block block(int dim, int dimOut, int groups, int rows, int cols) {
		return block(dim, dimOut, groups, rows, cols, null);
	}

	/**
	 * Creates a ResNet block with normalization and optional scale/shift.
	 *
	 * @param dim Input dimension
	 * @param dimOut Output dimension
	 * @param groups Number of normalization groups
	 * @param rows Number of rows for spatial dimensions
	 * @param cols Number of columns for spatial dimensions
	 * @param scaleShift Optional block producing scale and shift values
	 * @return Configured ResNet block
	 */
	protected Block block(int dim, int dimOut, int groups, int rows, int cols, Block scaleShift) {
		SequentialBlock block = new SequentialBlock(shape(batchSize, dim, rows, cols));
		block.add(convolution2d(dimOut, 3, 1));
		block.add(norm(groups));

		if (scaleShift != null) {
			if (scaleShift.getOutputShape().getDimensions() != 5 ||
					scaleShift.getOutputShape().length(1) != batchSize) {
				throw new IllegalArgumentException();
			}

			block.add(compose("scaleShift", scaleShift,
					(in, ss) -> {
						TraversalPolicy shape = scaleShift.getOutputShape().traverse(1).item();
						CollectionProducer scale =
								subset(shape.prependDimension(1), ss, 0, 0, 0, 0, 0);
						CollectionProducer shift =
								subset(shape.prependDimension(1), ss, 1, 0, 0, 0, 0);
						return multiply(in, scale.add(1.0)).add(shift);
					}));
		}

		block.add(silu());
		return block;
	}

	/**
	 * Creates a simple MLP block with SiLU activation.
	 *
	 * @param dim Input dimension
	 * @param dimOut Output dimension
	 * @return Configured MLP block
	 */
	protected Block mlp(int dim, int dimOut) {
		SequentialBlock mlp = new SequentialBlock(shape(batchSize, dim));
		mlp.add(silu());
		mlp.add(dense(dim, dimOut));
		return mlp;
	}

	/** Creates a ResNet block with default group count for rows and columns. */
	protected Block resNetBlock(int dim, int dimOut, int timeEmbedDim, Block time,
								int rows, int cols) {
		return resNetBlock(dim, dimOut, timeEmbedDim, time, 8, rows, cols);
	}

	/**
	 * Creates a function that produces ResNet blocks with specified group count.
	 *
	 * @param dim Input dimension
	 * @param dimOut Output dimension
	 * @param timeEmbedDim Time embedding dimension
	 * @param time Time embedding block
	 * @param groups Number of normalization groups
	 * @return Function producing ResNet blocks
	 */
	protected Function<TraversalPolicy, Block> resNetBlock(int dim, int dimOut, int timeEmbedDim,
														   Block time, int groups) {
		return shape -> {
			if (shape.getDimensions() != 4) {
				throw new IllegalArgumentException();
			}

			int rows = shape.length(2);
			int cols = shape.length(3);
			return resNetBlock(dim, dimOut, timeEmbedDim, time, groups, rows, cols);
		};
	}

	/**
	 * Creates a ResNet block with time embedding and optional scale/shift.
	 *
	 * @param dim Input dimension
	 * @param dimOut Output dimension
	 * @param timeEmbedDim Time embedding dimension
	 * @param time Time embedding block
	 * @param groups Number of normalization groups
	 * @param rows Number of rows for spatial dimensions
	 * @param cols Number of columns for spatial dimensions
	 * @return Configured ResNet block
	 */
	protected Block resNetBlock(int dim, int dimOut, int timeEmbedDim, Block time,
								int groups, int rows, int cols) {
		Block scaleShift = null;

		if (timeEmbedDim > 0) {
			Block mlp = time.andThen(mlp(timeEmbedDim, dimOut * 2));
			scaleShift = mlp
					.reshape(batchSize, 2, dimOut)
					.enumerate(shape(batchSize, 1, dimOut))
					.reshape(2, batchSize, dimOut, 1, 1);
		}

		SequentialBlock resNet = new SequentialBlock(shape(batchSize, dim, rows, cols));
		Block resConv = dim != dimOut ?
				resNet.branch(convolution2d(dim, dimOut, 1, 0)) : null;

		resNet.add(block(dim, dimOut, groups, rows, cols, scaleShift));
		resNet.add(block(dimOut, dimOut, groups, rows, cols));
		if (resConv != null) resNet.accum(resConv, false);
		log("\tResNet[" + dim + "," + dimOut + "]: " + resNet.getOutputShape());
		return resNet;
	}


	/**
	 * Creates a weighted sum function for attention output aggregation.
	 *
	 * @param v Value block
	 * @param heads Number of attention heads
	 * @param dimHead Dimension per head
	 * @param size Spatial size
	 * @return Function producing weighted sum aggregation
	 */
	@Override
	public Function<TraversalPolicy, CellularLayer> weightedSum(
			Block v, int heads, int dimHead, int size) {
		if (v.getOutputShape().getDimensions() != 4 ||
				v.getOutputShape().length(1) != heads ||
				v.getOutputShape().length(2) != dimHead ||
				v.getOutputShape().length(3) != size) {
			throw new IllegalArgumentException();
		}

		return compose("weightedSum", v, shape(batchSize, heads, size, dimHead),
				(a, b) -> {
					CollectionProducer pa = c(a)
							.traverse(4)
							.repeat(dimHead);
					CollectionProducer pb = c(b)
							.traverse(2)
							.enumerate(3, 1)
							.traverse(2)
							.repeat(size);
					return multiply(pa, pb)
							.reshape(batchSize, heads, size, size, dimHead)
							.traverse(3)
							.enumerate(4, 1)
							.sum(4);
				});
	}

	/**
	 * Creates an attention block with shape-inferred dimensions.
	 *
	 * @param dim Model dimension
	 * @return Function producing attention blocks
	 */
	public Function<TraversalPolicy, Block> attention(int dim) {
		return shape -> attention(dim, shape.length(1), shape.length(2), shape.length(3));
	}

	/**
	 * Creates an attention block with inferred head count and dimHead.
	 *
	 * @param dim Model dimension
	 * @param inputChannels Number of input channels
	 * @param rows Number of rows
	 * @param cols Number of columns
	 * @return Configured attention block
	 */
	public Block attention(int dim, int inputChannels, int rows, int cols) {
		return attention(dim, 4, 32, inputChannels, rows, cols);
	}

	/**
	 * Creates a multi-head attention block with QKV projection.
	 *
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param dimHead Dimension per head
	 * @param inputChannels Number of input channels
	 * @param rows Number of rows
	 * @param cols Number of columns
	 * @return Configured attention block
	 */
	public Block attention(int dim, int heads, int dimHead,
						   int inputChannels, int rows, int cols) {
		double scale = 1.0 / Math.sqrt(dimHead);
		int hiddenDim = dimHead * heads;
		int size = rows * cols;

		TraversalPolicy componentShape = shape(batchSize, heads, dimHead, size);

		SequentialBlock attention = new SequentialBlock(shape(batchSize, inputChannels, rows, cols));
		attention.add(convolution2d(dim, hiddenDim * 3, 1, 0, false));
		attention
				.reshape(batchSize, 3, hiddenDim * size)
				.enumerate(shape(batchSize, 1, hiddenDim * size))
				.reshape(3, batchSize, heads, dimHead, size);

		List<Block> qkv = attention.split(componentShape, 0);
		Block k = qkv.get(1);
		Block v = qkv.get(2);

		attention.add(scale(scale));
		attention.add(similarity(k, heads, size, size));
		attention.add(softmax(true));
		attention.add(weightedSum(v, heads, dimHead, size));
		attention.reshape(batchSize, size, hiddenDim)
				.enumerate(1, 2, 1)
				.reshape(batchSize, hiddenDim, rows, cols);
		attention.add(convolution2d(hiddenDim, dim, 1, 0));
		return attention;
	}

	/**
	 * Applies pre-normalization to a block-producing function.
	 *
	 * @param block Function producing blocks to normalize
	 * @return Function producing normalized blocks
	 */
	protected Function<TraversalPolicy, Block> preNorm(Function<TraversalPolicy, Block> block) {
		return shape -> preNorm(block.apply(shape));
	}

	/**
	 * Applies pre-normalization to an existing block.
	 *
	 * @param block Block to normalize
	 * @return Normalized block
	 */
	protected Block preNorm(Block block) {
		SequentialBlock out = new SequentialBlock(block.getInputShape());
		out.add(norm());
		out.add(block);
		return block;
	}

	/** Tests the ResNet block architecture with timestep embeddings. */
	@Test(timeout = 120000)
	@TestProperties(knownIssue = true)
	public void resNet() {

		int initDim = 28;
		int rows = 28, cols = 28;

		int timeInputDim = initDim;
		int timeEmbedDim = initDim * 4;

		Block timeEmbedding = timestepEmbeddings(timeInputDim, timeEmbedDim);

		CompiledModel resNet =
				new Model(shape(batchSize, channels, rows, cols))
					.addInput(timeEmbedding)
					.add(resNetBlock(batchSize, channels, timeEmbedDim, timeEmbedding, rows, cols))
					.compile();

		resNet.forward(
				new PackedCollection(batchSize, channels, rows, cols).randnFill(),
				new PackedCollection(batchSize, timeInputDim).randnFill());
	}

	/**
	 * Creates sinusoidal position embeddings.
	 *
	 * @param dim Embedding dimension
	 * @return Block producing sinusoidal position embeddings
	 */
	protected Block sinPositionEmbeddings(int dim) {
		int hd = dim / 2;
		double scale = Math.log(10000) / (hd - 1);

		PackedCollection values = new PackedCollection(hd).fill(pos -> pos[0] * -scale);

		return layer("sinEmbed", shape(batchSize, 1), shape(batchSize, dim), (in) -> {
			CollectionProducer embeddings =
					multiply(
							c(in).repeat(1, hd).reshape(batchSize, hd),
							cp(values).repeat(batchSize).reshape(batchSize, hd));
			return concat(shape(batchSize, dim).traverse(1),
						sin(embeddings.traverse(1)),
						cos(embeddings.traverse(1)));
		});
	}

	/** Creates sinusoidal timestep embeddings with default output length equal to input length. */
	protected Block sinTimestepEmbeddings(int dim, int timeLen) {
		return sinTimestepEmbeddings(dim, timeLen, timeLen);
	}

	/**
	 * Creates sinusoidal timestep embeddings with configurable output length.
	 *
	 * @param dim Embedding dimension
	 * @param timeLen Time input dimension
	 * @param outLen Output dimension
	 * @return Block producing sinusoidal timestep embeddings
	 */
	protected Block sinTimestepEmbeddings(int dim, int timeLen, int outLen) {
		SequentialBlock block = new SequentialBlock(shape(batchSize, 1));
		block.add(sinPositionEmbeddings(dim));
		block.add(dense(dim, timeLen));
		block.add(gelu());
		block.add(dense(timeLen, outLen));
		return block;
	}

	/** Creates a UNet model with default parameters. */
	protected Model unet(int dim) {
		return unet(dim, null, null, false, 4);
	}

	/**
	 * Creates a UNet model with full configuration options.
	 *
	 * @param dim Base model dimension
	 * @param initDim Initial channel dimension, or null to use dim
	 * @param outDim Output channel dimension, or null to use channels
	 * @param selfCondition Whether to enable self-conditioning
	 * @param resnetBlockGroups Number of groups for ResNet normalization
	 * @return Configured UNet model
	 */
	protected Model unet(int dim, Integer initDim, Integer outDim,
						boolean selfCondition, int resnetBlockGroups) {
		int width = dim, height = dim;

		int inputChannels = selfCondition ? channels * 2 : channels;
		int initDimValue = (initDim != null) ? initDim : dim;
		int outDimValue = (outDim != null) ? outDim : channels;

		Model unet = new Model(shape(batchSize, inputChannels, height, width));
		unet.add(convolution2d(inputChannels, initDimValue, 1, 0));

		int[] dims = new int[dimFactors.length + 1];
		dims[0] = initDimValue;
		for (int i = 0; i < dimFactors.length; i++) {
			dims[i + 1] = dim * dimFactors[i];
		}

		int timeDim = dim * 4;
		Block timeMlp = sinTimestepEmbeddings(dim, timeDim);
		unet.addInput(timeMlp);

		SequentialBlock main = unet.sequential();
		Block residual = main.branch();

		Deque<Block> featureMaps = new ArrayDeque<>();

		for (int i = 0; i < dims.length - 1; i++) {
			boolean isLast = i >= dims.length - 2;
			SequentialBlock downBlock = new SequentialBlock(main.getOutputShape());
			log("DownBlock[" + i + "]: " + dims[i] + " -> " + dims[i + 1]);

			downBlock.add(resNetBlock(dims[i], dims[i], timeDim, timeMlp.branch(), resnetBlockGroups));
			featureMaps.push(downBlock.branch());
			log("\t\tAdded feature map " + featureMaps.peek().getOutputShape());

			downBlock.add(resNetBlock(dims[i], dims[i], timeDim, timeMlp.branch(), resnetBlockGroups));
			downBlock.add(residual(preNorm(linearAttention(dims[i]))));
			featureMaps.push(downBlock.branch());
			log("\t\tAdded feature map " + featureMaps.peek().getOutputShape());

			if (!isLast) {
				downBlock.add(downsample(dims[i], dims[i + 1]));
			} else {
				downBlock.add(convolution2d(dims[i], dims[i + 1], 3, 1));
			}

			main.add(downBlock);
		}

		main.add(resNetBlock(dims[dims.length - 1], dims[dims.length - 1], timeDim, timeMlp.branch(), resnetBlockGroups));
		main.add(residual(preNorm(attention(dims[dims.length - 1]))));
		main.add(resNetBlock(dims[dims.length - 1], dims[dims.length - 1], timeDim, timeMlp.branch(), resnetBlockGroups));

		for (int i = dims.length - 1; i > 0; i--) {
			boolean isLast = i == 1;
			SequentialBlock upBlock = new SequentialBlock(main.getOutputShape());

			int dimIn = dims[i - 1];
			int dimOut = dims[i];
			int totalDim = dimIn + dimOut;
			log("UpBlock[" + i + "]: (" + dimIn + "+" + dimOut + ") " + totalDim + " -> " + dimOut);

			log(upBlock.getOutputShape());
			log("\t\tConnecting feature map " + featureMaps.peek().getOutputShape());
			upBlock.add(concat(1, featureMaps.pop()));
			upBlock.add(resNetBlock(totalDim, dimOut, timeDim, timeMlp.branch(), resnetBlockGroups));

			log(upBlock.getOutputShape());
			log("\t\tConnecting feature map " + featureMaps.peek().getOutputShape());
			upBlock.add(concat(1, featureMaps.pop()));
			upBlock.add(resNetBlock(totalDim, dimOut, timeDim, timeMlp.branch(), resnetBlockGroups));
			upBlock.add(residual(preNorm(linearAttention(dimOut))));

			if (!isLast) {
				upBlock.add(upsample(dimOut, dimIn));
			} else {
				upBlock.add(convolution2d(dimOut, dimIn, 3, 1));
			}

			main.add(upBlock);
		}

		main.add(concat(1, residual));
		main.add(resNetBlock(dim * 2, dim, timeDim, timeMlp.branch(), resnetBlockGroups));
		main.add(convolution2d(dim, outDimValue, 1, 0));
		return unet;
	}

	/**
	 * Generates a linear beta schedule for diffusion timesteps.
	 *
	 * @param timesteps Number of diffusion timesteps
	 * @return CollectionProducer producing beta values
	 */
	public CollectionProducer linearBetaSchedule(int timesteps) {
		double betaStart = 0.0001;
		double betaEnd = 0.02;
		return linear(betaStart, betaEnd, timesteps);
	}

	/**
	 * Extracts values from a schedule at specified timesteps.
	 *
	 * @param a Schedule to extract from
	 * @param t Timesteps at which to extract
	 * @param xShape Shape of the data tensor
	 * @return Extracted values reshaped to match data dimensions
	 */
	public CollectionProducer extract(CollectionProducer a,
									  CollectionProducer t,
									  TraversalPolicy xShape) {
		if (t.getShape().getDimensions() != 1) {
			throw new IllegalArgumentException();
		}

		int batches = t.getShape().length(0);
		CollectionProducer out = a.traverseAll().valueAt(t);

		int depth = xShape.getDimensions();
		TraversalPolicy resultShape =
				padDimensions(shape(batches), 1, depth, true);
		return out.reshape(resultShape);
	}

	/**
	 * Transforms image data to model input range [-1, 1].
	 *
	 * @param image Input image
	 * @return Transform image data
	 */
	public CollectionProducer imageTransform(CollectionProducer image) {
		return image.multiply(2).subtract(1.0);
	}

	/**
	 * Reverses image transformation, converting from [-1, 1] to [0, 1].
	 *
	 * @param data Input data
	 * @return Normalized image data
	 */
	public CollectionProducer imageTransformReverse(Producer<PackedCollection> data) {
		return c(data).add(1.0).divide(2);
	}

	/** Generates noisy sample with default random noise. */
	public CollectionProducer qSample(
			CollectionProducer xStart,
			CollectionProducer t) {
		return qSample(xStart, t, null);
	}

	/**
	 * Generates a noisy sample using the q-sampling process.
	 *
	 * @param xStart Original image data
	 * @param t Timesteps for noisy sample
	 * @param noise Optional noise, generates random if null
	 * @return Noisy image at timestep t
	 */
	public CollectionProducer qSample(
														   CollectionProducer xStart,
														   CollectionProducer t,
														   Producer<PackedCollection> noise) {
		if (noise == null) {
			noise = randn(shape(xStart));
		}

		CollectionProducer sqrtAlphasCumProdT =
				extract(cp(sqrtAlphasCumProd), t, shape(xStart));
		CollectionProducer sqrtOneMinusAlphasCumProdT =
				extract(cp(sqrtOneMinusAlphasCumProd), t, shape(xStart));
		return xStart.multiply(sqrtAlphasCumProdT)
				.add(sqrtOneMinusAlphasCumProdT.multiply(noise));
	}

	/**
	 * Gets a noisy version of an image at specified timesteps.
	 *
	 * @param xStart Original image data
	 * @param t Timesteps for noise generation
	 * @return Noisy image transformed back to [0, 1] range
	 */
	public CollectionProducer getNoisyImage(
																 CollectionProducer xStart,
																 CollectionProducer t) {
		TraversalPolicy shape = shape(xStart);
		Evaluable<PackedCollection> qSample = qSample(
				cv(shape, 0),
				cv(shape(batchSize), 1),
				cv(shape, 2)).get();

		Random randn = randn(shape);
		PackedCollection xIn = xStart.evaluate();
		PackedCollection tIn = t.evaluate();
		PackedCollection noise = randn.evaluate();
		PackedCollection xNoisy = qSample.evaluate(xIn, tIn, noise);
		return imageTransformReverse(cp(xNoisy));
	}

	/** Tests image transformation and noise addition pipeline. */
	@Test(timeout = 120000)
	public void imageTransform() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		CollectionProducer data =
				imageTransform(channels(new File("/Users/michael/Desktop/output_cats_128.jpg")));
		log(data.getShape());

		PackedCollection t = pack(299);
		Producer<PackedCollection> p = (Producer) Process.optimized(getNoisyImage(data, cp(t)));

		saveChannels("results/test_out.png", p).get().run();
	}

	/**
	 * Loads input images from a directory as a collection of batches.
	 *
	 * @param imagesDir Directory containing image files
	 * @param shape Shape for each batch
	 * @return Iterable of batched image data
	 * @throws IOException If directory cannot be read
	 */
	public Iterable<PackedCollection> loadInputs(File imagesDir, TraversalPolicy shape) throws IOException {
		TraversalPolicy item = shape.traverse(1).item();
		List<PackedCollection> data = new ArrayList<>();

		int n = 0;
		PackedCollection current = new PackedCollection(shape.traverse(1));

		f: for (File file : Objects.requireNonNull(imagesDir.listFiles())) {
			if (!file.getName().endsWith(".png")) continue f;

			PackedCollection input;

			if (channels == 1) {
				input = GraphicsConverter.loadGrayscale(file).reshape(item);
			} else {
				input = GraphicsConverter.loadChannels(file).reshape(item);
			}

			current.set(n, input);
			n++;

			if (n >= batchSize) {
				data.add(current);
				current = new PackedCollection(shape.traverse(1));
				n = 0;
			}
		}

		return data;
	}

	/**
	 * Creates a dataset for UNet training with q-sampling pairs.
	 *
	 * @param imagesDir Directory containing training images
	 * @param shape Shape for each batch
	 * @return Dataset producing (noisy_image, noise) pairs with timesteps
	 * @throws IOException If directory cannot be read
	 */
	public Dataset<PackedCollection> loadDataset(File imagesDir, TraversalPolicy shape) throws IOException {
		Evaluable<PackedCollection> qSample = qSample(
												cv(shape, 0),
												cv(shape(batchSize), 1),
												cv(shape, 2)).get();
		Random randn = randn(shape);

		return Dataset.of(loadInputs(imagesDir, shape), xStart -> {
			if (!xStart.getShape().equalsIgnoreAxis(shape)) {
				throw new IllegalArgumentException();
			}

			randn.refresh();
			PackedCollection noise = randn.evaluate();
			PackedCollection t = new PackedCollection(batchSize, 1)
					.fill(() -> (int) (Math.random() * timesteps));
			PackedCollection xNoisy = qSample.evaluate(xStart.each(), t, noise.each());
			List<ValueTarget<PackedCollection>> result = new ArrayList<>();
			result.add(ValueTarget.of(xNoisy, noise).withArguments(t));
			return result;
		});
	}

	/**
	 * Runs UNet training with profiling support.
	 *
	 * @param dim Model dimension
	 * @param profile Operation profile node for performance tracking
	 */
	public void runUnet(int dim, OperationProfileNode profile) {
		try {
			File images = new File("generated_images_28");
			Dataset<PackedCollection> all = loadDataset(images, shape(batchSize, channels, dim, dim).traverseEach());
			List<Dataset<PackedCollection>> split = all.split(0.4);

			Model unet = unet(dim);
			unet.setParameterUpdate(new AdamOptimizer(1e-3, 0.9, 0.999));

			CompiledModel model = unet.compile(profile);
			ModelOptimizer optimizer = new ModelOptimizer(model, () -> split.get(0));
			optimizer.setLogFrequency(1);
			optimizer.setLogConsumer(msg -> alert("UNet: " + msg));

			int iterations = 26;
			optimizer.optimize(iterations);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Tests the complete UNet model with training. */
	@Test(timeout = 120000)
	public void unet() throws IOException {
		int dim = 28;

		OperationProfileNode profile = new OperationProfileNode("unet");
		boolean failed = false;

		try {
			profile(profile, () -> runUnet(dim, profile));
		} catch (Exception e) {
			alert("UNet test failed", e);
			failed = true;
		} finally {
			if (!failed)
				alert("UNet test completed");

			profile.save("results/unet.xml");
		}
	}
}
