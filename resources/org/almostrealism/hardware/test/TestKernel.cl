__kernel void testKernel(__global double *res, __global const double *r,
                        const int resOffset, const int rOffset,
                        const int resSize, const int rSize) {
    res[get_global_id(0) * resSize + resOffset]     = r[get_global_id(0) * rSize + rOffset];
    res[get_global_id(0) * resSize + resOffset + 1] = r[get_global_id(0) * rSize + rOffset + 1];
    res[get_global_id(0) * resSize + resOffset + 2] = r[get_global_id(0) * rSize + rOffset + 2];
    res[get_global_id(0) * resSize + resOffset + 3] = r[get_global_id(0) * rSize + rOffset + 3];
    res[get_global_id(0) * resSize + resOffset + 4] = r[get_global_id(0) * rSize + rOffset + 4];
    res[get_global_id(0) * resSize + resOffset + 5] = r[get_global_id(0) * rSize + rOffset + 5];
}