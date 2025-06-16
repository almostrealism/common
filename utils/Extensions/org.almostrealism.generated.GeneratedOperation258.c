#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation258_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2967_v2813Offset = (int) offsetArr[0];
jint _2951_v2807Offset = (int) offsetArr[1];
jint _2967_v2813Size = (int) sizeArr[0];
jint _2951_v2807Size = (int) sizeArr[1];
jint _2967_v2813Dim0 = (int) dim0Arr[0];
jint _2951_v2807Dim0 = (int) dim0Arr[1];
double *_2967_v2813 = ((double *) argArr[0]);
double *_2951_v2807 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2967_v2813[global_id + _2967_v2813Offset] = 1.0 / (exp(- _2951_v2807[global_id + _2951_v2807Offset]) + 1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
