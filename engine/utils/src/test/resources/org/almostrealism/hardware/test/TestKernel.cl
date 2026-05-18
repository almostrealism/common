__kernel void testKernel(__global double *res, __global const double *r,
                        const int resOffset, const int rOffset,
                        const int resSize, const int rSize,
                        const int resDim0, const int rDim0) {
    res[get_global_id(0) * resDim0 + resOffset]     = r[get_global_id(0) * rDim0 + rOffset];
    res[get_global_id(0) * resDim0 + resOffset + 1] = r[get_global_id(0) * rDim0 + rOffset + 1];
    res[get_global_id(0) * resDim0 + resOffset + 2] = r[get_global_id(0) * rDim0 + rOffset + 2];
    res[get_global_id(0) * resDim0 + resOffset + 3] = r[get_global_id(0) * rDim0 + rOffset + 3];
    res[get_global_id(0) * resDim0 + resOffset + 4] = r[get_global_id(0) * rDim0 + rOffset + 4];
    res[get_global_id(0) * resDim0 + resOffset + 5] = r[get_global_id(0) * rDim0 + rOffset + 5];
}