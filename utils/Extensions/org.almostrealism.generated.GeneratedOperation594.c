#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation594_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9136_v5825Offset = (int) offsetArr[0];
jint _9160_v5828Offset = (int) offsetArr[1];
jint _9163_v5833Offset = (int) offsetArr[2];
jint _9136_v5825Size = (int) sizeArr[0];
jint _9160_v5828Size = (int) sizeArr[1];
jint _9163_v5833Size = (int) sizeArr[2];
jint _9136_v5825Dim0 = (int) dim0Arr[0];
jint _9160_v5828Dim0 = (int) dim0Arr[1];
jint _9163_v5833Dim0 = (int) dim0Arr[2];
double *_9136_v5825 = ((double *) argArr[0]);
double *_9160_v5828 = ((double *) argArr[1]);
double *_9163_v5833 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9163_v5833[global_id + _9163_v5833Offset] = (- ((_9160_v5828[(global_id * 8) + _9160_v5828Offset + 1] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 2] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 3] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 4] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 5] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 6] + _9160_v5828[(global_id * 8) + _9160_v5828Offset + 7] + _9160_v5828[(global_id * 8) + _9160_v5828Offset]) * _9136_v5825[_9136_v5825Offset])) + _9163_v5833[global_id + _9163_v5833Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
