#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation807_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12234_v8035Offset = (int) offsetArr[0];
jint _12229_v8032Offset = (int) offsetArr[1];
jint _12234_v8035Size = (int) sizeArr[0];
jint _12229_v8032Size = (int) sizeArr[1];
jint _12234_v8035Dim0 = (int) dim0Arr[0];
jint _12229_v8032Dim0 = (int) dim0Arr[1];
double *_12234_v8035 = ((double *) argArr[0]);
double *_12229_v8032 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12234_v8035[global_id + _12234_v8035Offset] = (_12229_v8032[global_id + _12229_v8032Offset + 20] + -0.04074233426820554) / 0.03027805089284251;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
