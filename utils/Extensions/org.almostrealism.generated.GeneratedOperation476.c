#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation476_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6313_v4725Offset = (int) offsetArr[0];
jint _6313_v4726Offset = (int) offsetArr[1];
jint _6313_v4728Offset = (int) offsetArr[2];
jint _6313_v4725Size = (int) sizeArr[0];
jint _6313_v4726Size = (int) sizeArr[1];
jint _6313_v4728Size = (int) sizeArr[2];
jint _6313_v4725Dim0 = (int) dim0Arr[0];
jint _6313_v4726Dim0 = (int) dim0Arr[1];
jint _6313_v4728Dim0 = (int) dim0Arr[2];
double *_6313_v4725 = ((double *) argArr[0]);
double *_6313_v4726 = ((double *) argArr[1]);
double *_6313_v4728 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6313_v4725[global_id + _6313_v4725Offset] = _6313_v4726[global_id + _6313_v4726Offset] * _6313_v4728[global_id + _6313_v4728Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
