#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation202_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2562_v2366Offset = (int) offsetArr[0];
jint _2553_v2363Offset = (int) offsetArr[1];
jint _2562_v2366Size = (int) sizeArr[0];
jint _2553_v2363Size = (int) sizeArr[1];
jint _2562_v2366Dim0 = (int) dim0Arr[0];
jint _2553_v2363Dim0 = (int) dim0Arr[1];
double *_2562_v2366 = ((double *) argArr[0]);
double *_2553_v2363 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2562_v2366[(global_id * _2562_v2366Dim0) + _2562_v2366Offset] = (((- ((global_id * 4) % 4)) + (global_id % 4)) == 0) ? _2553_v2363[(((global_id / 4) * _2553_v2363Dim0) + (global_id % 2)) + _2553_v2363Offset] : 0;
_2562_v2366[(global_id * _2562_v2366Dim0) + _2562_v2366Offset + 1] = ((global_id % 4) == 1) ? _2553_v2363[(((global_id / 4) * _2553_v2363Dim0) + (global_id % 2)) + _2553_v2363Offset] : 0;
_2562_v2366[(global_id * _2562_v2366Dim0) + _2562_v2366Offset + 2] = ((global_id % 4) == 2) ? _2553_v2363[(((global_id / 4) * _2553_v2363Dim0) + (global_id % 2)) + _2553_v2363Offset] : 0;
_2562_v2366[(global_id * _2562_v2366Dim0) + _2562_v2366Offset + 3] = ((global_id % 4) == 3) ? _2553_v2363[(((global_id / 4) * _2553_v2363Dim0) + (global_id % 2)) + _2553_v2363Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
