#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation701_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10900_v7153Offset = (int) offsetArr[0];
jint _10895_v7145Offset = (int) offsetArr[1];
jint _10896_v7146Offset = (int) offsetArr[2];
jint _10899_v7152Offset = (int) offsetArr[3];
jint _10900_v7153Size = (int) sizeArr[0];
jint _10895_v7145Size = (int) sizeArr[1];
jint _10896_v7146Size = (int) sizeArr[2];
jint _10899_v7152Size = (int) sizeArr[3];
jint _10900_v7153Dim0 = (int) dim0Arr[0];
jint _10895_v7145Dim0 = (int) dim0Arr[1];
jint _10896_v7146Dim0 = (int) dim0Arr[2];
jint _10899_v7152Dim0 = (int) dim0Arr[3];
double *_10900_v7153 = ((double *) argArr[0]);
double *_10895_v7145 = ((double *) argArr[1]);
double *_10896_v7146 = ((double *) argArr[2]);
double *_10899_v7152 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10900_v7153[(global_id * _10900_v7153Dim0) + _10900_v7153Offset] = ((- _10896_v7146[(((global_id / _10896_v7146Size) * _10896_v7146Dim0) + (global_id % _10896_v7146Size)) + _10896_v7146Offset]) + _10895_v7145[(((global_id / 2) * _10895_v7145Dim0) + (global_id % 2)) + _10895_v7145Offset]) / _10899_v7152[(((global_id / _10899_v7152Size) * _10899_v7152Dim0) + (global_id % _10899_v7152Size)) + _10899_v7152Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
