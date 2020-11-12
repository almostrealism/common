__kernel void xyz(__global double *bank, __global double *cursors,
                    const int bankOffset, const int cursorsOffset,
                    const int bankSize, const int cursorsSize) {
	if (bank[bankOffset + 1] - bank[bankOffset] <= 0) return;

	for (int i = bank[bankOffset] + 1; i < bank[bankOffset + 1]; i++) {
		if (bank[2 * i] > cursors[cursorsOffset]) {
			bank[bankOffset] = i - 1;
			return;
		}
	}
}