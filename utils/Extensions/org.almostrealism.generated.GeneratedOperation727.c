#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation727_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11252_v7209Offset = (int) offsetArr[0];
jint _11250_v7204Offset = (int) offsetArr[1];
jint _11250_v7205Offset = (int) offsetArr[2];
jint _11251_v7207Offset = (int) offsetArr[3];
jint _11252_v7209Size = (int) sizeArr[0];
jint _11250_v7204Size = (int) sizeArr[1];
jint _11250_v7205Size = (int) sizeArr[2];
jint _11251_v7207Size = (int) sizeArr[3];
jint _11252_v7209Dim0 = (int) dim0Arr[0];
jint _11250_v7204Dim0 = (int) dim0Arr[1];
jint _11250_v7205Dim0 = (int) dim0Arr[2];
jint _11251_v7207Dim0 = (int) dim0Arr[3];
double *_11252_v7209 = ((double *) argArr[0]);
double *_11250_v7204 = ((double *) argArr[1]);
double *_11250_v7205 = ((double *) argArr[2]);
double *_11251_v7207 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11252_v7209[global_id + _11252_v7209Offset] = (_11250_v7204[global_id + _11250_v7204Offset] + _11250_v7205[global_id + _11250_v7205Offset]) * _11251_v7207[(global_id / 120) + _11251_v7207Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
