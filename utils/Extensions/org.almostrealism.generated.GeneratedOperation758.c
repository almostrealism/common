#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation758_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11686_v7528Offset = (int) offsetArr[0];
jint _11636_v7516Offset = (int) offsetArr[1];
jint _11639_v7521Offset = (int) offsetArr[2];
jint _11685_v7527Offset = (int) offsetArr[3];
jint _11686_v7528Size = (int) sizeArr[0];
jint _11636_v7516Size = (int) sizeArr[1];
jint _11639_v7521Size = (int) sizeArr[2];
jint _11685_v7527Size = (int) sizeArr[3];
jint _11686_v7528Dim0 = (int) dim0Arr[0];
jint _11636_v7516Dim0 = (int) dim0Arr[1];
jint _11639_v7521Dim0 = (int) dim0Arr[2];
jint _11685_v7527Dim0 = (int) dim0Arr[3];
double *_11686_v7528 = ((double *) argArr[0]);
double *_11636_v7516 = ((double *) argArr[1]);
double *_11639_v7521 = ((double *) argArr[2]);
double *_11685_v7527 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11686_v7528[global_id + _11686_v7528Offset] = ((- (_11636_v7516[(global_id / 2500) + _11636_v7516Offset] / 25.0)) + _11639_v7521[(global_id / 100) + _11639_v7521Offset]) * _11685_v7527[(((global_id / 2500) * 100) + (global_id % 100)) + _11685_v7527Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
