#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation722_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11238_v7226Offset = (int) offsetArr[0];
jint _11188_v7214Offset = (int) offsetArr[1];
jint _11191_v7219Offset = (int) offsetArr[2];
jint _11237_v7225Offset = (int) offsetArr[3];
jint _11238_v7226Size = (int) sizeArr[0];
jint _11188_v7214Size = (int) sizeArr[1];
jint _11191_v7219Size = (int) sizeArr[2];
jint _11237_v7225Size = (int) sizeArr[3];
jint _11238_v7226Dim0 = (int) dim0Arr[0];
jint _11188_v7214Dim0 = (int) dim0Arr[1];
jint _11191_v7219Dim0 = (int) dim0Arr[2];
jint _11237_v7225Dim0 = (int) dim0Arr[3];
double *_11238_v7226 = ((double *) argArr[0]);
double *_11188_v7214 = ((double *) argArr[1]);
double *_11191_v7219 = ((double *) argArr[2]);
double *_11237_v7225 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11238_v7226[global_id + _11238_v7226Offset] = ((- (_11188_v7214[(global_id / 3600) + _11188_v7214Offset] / 30.0)) + _11191_v7219[(global_id / 120) + _11191_v7219Offset]) * _11237_v7225[(((global_id / 3600) * 120) + (global_id % 120)) + _11237_v7225Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
