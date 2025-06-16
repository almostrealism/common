#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation200_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2548_v2341Offset = (int) offsetArr[0];
jint _2545_v2334Offset = (int) offsetArr[1];
jint _2547_v2338Offset = (int) offsetArr[2];
jint _2548_v2341Size = (int) sizeArr[0];
jint _2545_v2334Size = (int) sizeArr[1];
jint _2547_v2338Size = (int) sizeArr[2];
jint _2548_v2341Dim0 = (int) dim0Arr[0];
jint _2545_v2334Dim0 = (int) dim0Arr[1];
jint _2547_v2338Dim0 = (int) dim0Arr[2];
double *_2548_v2341 = ((double *) argArr[0]);
double *_2545_v2334 = ((double *) argArr[1]);
double *_2547_v2338 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2548_v2341[(global_id * _2548_v2341Dim0) + _2548_v2341Offset] = 0.0;
for (int _2548_i = 0; _2548_i < 2;) {
jint k_2548_i = (global_id * 2) + _2548_i;
_2548_v2341[(global_id * _2548_v2341Dim0) + _2548_v2341Offset] = (_2545_v2334[((((k_2548_i) / 4) * _2545_v2334Dim0) + ((k_2548_i) % 2)) + _2545_v2334Offset] * _2547_v2338[((k_2548_i) % 4) + _2547_v2338Offset]) + _2548_v2341[(global_id * _2548_v2341Dim0) + _2548_v2341Offset];
_2548_i = _2548_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
