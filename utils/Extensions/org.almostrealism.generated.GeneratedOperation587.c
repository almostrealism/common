#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation587_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9127_v5917Offset = (int) offsetArr[0];
jint _9077_v5887Offset = (int) offsetArr[1];
jint _9122_v5907Offset = (int) offsetArr[2];
jint _9127_v5917Size = (int) sizeArr[0];
jint _9077_v5887Size = (int) sizeArr[1];
jint _9122_v5907Size = (int) sizeArr[2];
jint _9127_v5917Dim0 = (int) dim0Arr[0];
jint _9077_v5887Dim0 = (int) dim0Arr[1];
jint _9122_v5907Dim0 = (int) dim0Arr[2];
double *_9127_v5917 = ((double *) argArr[0]);
double *_9077_v5887 = ((double *) argArr[1]);
double *_9122_v5907 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9127_v5917[global_id + _9127_v5917Offset] = pow(pow(((_9077_v5887[((global_id / 16) * 2) + _9077_v5887Offset + 1] + _9077_v5887[((global_id / 16) * 2) + _9077_v5887Offset]) / 2.0) + 1.0E-5, 0.5), -1.0) * (((_9122_v5907[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9122_v5907Offset + 1] + _9122_v5907[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9122_v5907Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
