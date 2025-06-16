#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation732_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11293_v7401Offset = (int) offsetArr[0];
jint _11288_v7398Offset = (int) offsetArr[1];
jint _11293_v7401Size = (int) sizeArr[0];
jint _11288_v7398Size = (int) sizeArr[1];
jint _11293_v7401Dim0 = (int) dim0Arr[0];
jint _11288_v7398Dim0 = (int) dim0Arr[1];
double *_11293_v7401 = ((double *) argArr[0]);
double *_11288_v7398 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11293_v7401[global_id + _11293_v7401Offset] = (_11288_v7398[global_id + _11288_v7398Offset] + -0.051094616789005426) / 0.029183803871196765;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
