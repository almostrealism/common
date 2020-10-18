__kernel void
push(__global double *res, __global const double *protein,
    const int resOffset, const int proteinOffset,
    const int resSize, const int proteinSize) {
	res[resOffset] = res[resOffset] + protein[proteinOffset];
}