#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation317_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3939_v3342Offset = (int) offsetArr[0];
jint _3934_v3339Offset = (int) offsetArr[1];
jint _3939_v3342Size = (int) sizeArr[0];
jint _3934_v3339Size = (int) sizeArr[1];
jint _3939_v3342Dim0 = (int) dim0Arr[0];
jint _3934_v3339Dim0 = (int) dim0Arr[1];
double *_3939_v3342 = ((double *) argArr[0]);
double *_3934_v3339 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3939_v3342[global_id + _3939_v3342Offset] = (_3934_v3339[global_id + _3934_v3339Offset] + -0.06762605360258835) / 0.012046717283587385;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
