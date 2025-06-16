#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation127_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1794_v1715Offset = (int) offsetArr[0];
jint _1793_v1713Offset = (int) offsetArr[1];
jint _1794_v1716Offset = (int) offsetArr[2];
jint _1794_v1715Size = (int) sizeArr[0];
jint _1793_v1713Size = (int) sizeArr[1];
jint _1794_v1716Size = (int) sizeArr[2];
jint _1794_v1715Dim0 = (int) dim0Arr[0];
jint _1793_v1713Dim0 = (int) dim0Arr[1];
jint _1794_v1716Dim0 = (int) dim0Arr[2];
double *_1794_v1715 = ((double *) argArr[0]);
double *_1793_v1713 = ((double *) argArr[1]);
double *_1794_v1716 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1794_v1715[global_id + _1794_v1715Offset] = _1793_v1713[(global_id % 64) + _1793_v1713Offset] * _1794_v1716[global_id + _1794_v1716Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
