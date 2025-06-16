#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation771_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11786_v7733Offset = (int) offsetArr[0];
jint _11781_v7730Offset = (int) offsetArr[1];
jint _11786_v7733Size = (int) sizeArr[0];
jint _11781_v7730Size = (int) sizeArr[1];
jint _11786_v7733Dim0 = (int) dim0Arr[0];
jint _11781_v7730Dim0 = (int) dim0Arr[1];
double *_11786_v7733 = ((double *) argArr[0]);
double *_11781_v7730 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11786_v7733[global_id + _11786_v7733Offset] = (_11781_v7730[global_id + _11781_v7730Offset + 25] + -0.05817943207369272) / 0.03311907779806383;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
