#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation571_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _8079_v5813Offset = (int) offsetArr[0];
jint _8075_v5805Offset = (int) offsetArr[1];
jint _8078_v5810Offset = (int) offsetArr[2];
jint _8079_v5813Size = (int) sizeArr[0];
jint _8075_v5805Size = (int) sizeArr[1];
jint _8078_v5810Size = (int) sizeArr[2];
jint _8079_v5813Dim0 = (int) dim0Arr[0];
jint _8075_v5805Dim0 = (int) dim0Arr[1];
jint _8078_v5810Dim0 = (int) dim0Arr[2];
double *_8079_v5813 = ((double *) argArr[0]);
double *_8075_v5805 = ((double *) argArr[1]);
double *_8078_v5810 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_8079_v5813[global_id + _8079_v5813Offset] = ((- (_8075_v5805[(global_id / 30) + _8075_v5805Offset] / 30.0)) + _8078_v5810[global_id + _8078_v5810Offset]) * ((- (_8075_v5805[(global_id / 30) + _8075_v5805Offset] / 30.0)) + _8078_v5810[global_id + _8078_v5810Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
