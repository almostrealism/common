#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation567_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7997_v5720Offset = (int) offsetArr[0];
jint _7981_v5701Offset = (int) offsetArr[1];
jint _7984_v5706Offset = (int) offsetArr[2];
jint _7992_v5711Offset = (int) offsetArr[3];
jint _7997_v5720Size = (int) sizeArr[0];
jint _7981_v5701Size = (int) sizeArr[1];
jint _7984_v5706Size = (int) sizeArr[2];
jint _7992_v5711Size = (int) sizeArr[3];
jint _7997_v5720Dim0 = (int) dim0Arr[0];
jint _7981_v5701Dim0 = (int) dim0Arr[1];
jint _7984_v5706Dim0 = (int) dim0Arr[2];
jint _7992_v5711Dim0 = (int) dim0Arr[3];
double *_7997_v5720 = ((double *) argArr[0]);
double *_7981_v5701 = ((double *) argArr[1]);
double *_7984_v5706 = ((double *) argArr[2]);
double *_7992_v5711 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7997_v5720[global_id + _7997_v5720Offset] = ((- (_7981_v5701[(global_id / 30) + _7981_v5701Offset] / 30.0)) + _7984_v5706[global_id + _7984_v5706Offset]) / pow((_7992_v5711[(global_id / 30) + _7992_v5711Offset] / 30.0) + 1.0E-5, 0.5);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
