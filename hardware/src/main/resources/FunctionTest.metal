[[kernel]] void vectorMatrixMultiply(const device float *vector [[buffer(0)]],
                                 const device float *matrix [[buffer(1)]],
                                 device float *result [[buffer(2)]],
                                 uint index [[thread_position_in_grid]],
                                 uint numElements [[threads_per_grid]]) {
    // Perform the vector-matrix multiplication
    float sum = 0.0;
    for (uint i = 0; i < 4; i++) {
         sum += vector[i] * matrix[i * numElements + index];
    }

    // Store the result
    result[index] = sum;
}