import org.almostrealism.Ops
import org.almostrealism.collect.PackedCollection
import org.almostrealism.model.Model

def ml = Ops.ops()

def shape = ml.shape(100, 100)
def model = new Model(shape)
model.addLayer(ml.convolution2d(3, 8))
model.addLayer(ml.pool2d(2))
model.add(ml.flatten())
model.addLayer(ml.dense(10))
model.addLayer(ml.softmax())

def input = new PackedCollection(shape)
model.setup().get().run()
model.forward(input)
