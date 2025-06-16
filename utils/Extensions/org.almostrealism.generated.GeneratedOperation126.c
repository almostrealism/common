#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation126_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1792_v1709Offset = (int) offsetArr[0];
jint _1777_v1701Offset = (int) offsetArr[1];
jint _1792_v1709Size = (int) sizeArr[0];
jint _1777_v1701Size = (int) sizeArr[1];
jint _1792_v1709Dim0 = (int) dim0Arr[0];
jint _1777_v1701Dim0 = (int) dim0Arr[1];
double *_1792_v1709 = ((double *) argArr[0]);
double *_1777_v1701 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1792_v1709[global_id + _1792_v1709Offset] = (1.0 / (exp(- _1777_v1701[global_id + _1777_v1701Offset]) + 1.0)) * _1777_v1701[global_id + _1777_v1701Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
