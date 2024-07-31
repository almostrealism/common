package org.almostrealism.ml

import org.almostrealism.CodeFeatures
import org.almostrealism.algebra.Tensor
import io.almostrealism.collect.TraversalPolicy
import org.almostrealism.model.Model

fun main() {
    ModelDemo().train()
}

class ModelDemo : CodeFeatures {
    fun model(inputShape: TraversalPolicy?): Model {
        val model = Model(inputShape)
        model.addLayer(convolution2d(8, 3))
        model.addLayer(pool2d(2))
        model.addBlock(flattened())
        model.addLayer(dense(10))
        model.addLayer(softmax())
        return model
    }

    fun train() {
        val shape = shape(100, 100)
        val model = model(shape)
        val t = loadImage(shape)
        val input = t.pack()
        model.compile().forward(input)
    }

    fun loadImage(shape: TraversalPolicy): Tensor<Double> {
        val t = Tensor<Double>()
        shape.stream().forEach { pos: IntArray -> t.insert(Math.random(), *pos) }
        return t
    }
}