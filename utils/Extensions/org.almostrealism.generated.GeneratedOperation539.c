#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation539_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7309_v5349Offset = (int) offsetArr[0];
jint _7304_v5346Offset = (int) offsetArr[1];
jint _7309_v5349Size = (int) sizeArr[0];
jint _7304_v5346Size = (int) sizeArr[1];
jint _7309_v5349Dim0 = (int) dim0Arr[0];
jint _7304_v5346Dim0 = (int) dim0Arr[1];
double *_7309_v5349 = ((double *) argArr[0]);
double *_7304_v5346 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7309_v5349[global_id + _7309_v5349Offset] = (_7304_v5346[global_id + _7304_v5346Offset] + -0.052091783799386705) / 0.04671084365124939;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
