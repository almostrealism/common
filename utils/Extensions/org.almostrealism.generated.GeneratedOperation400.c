#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation400_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5317_v4130Offset = (int) offsetArr[0];
jint _5313_v4122Offset = (int) offsetArr[1];
jint _5316_v4127Offset = (int) offsetArr[2];
jint _5317_v4130Size = (int) sizeArr[0];
jint _5313_v4122Size = (int) sizeArr[1];
jint _5316_v4127Size = (int) sizeArr[2];
jint _5317_v4130Dim0 = (int) dim0Arr[0];
jint _5313_v4122Dim0 = (int) dim0Arr[1];
jint _5316_v4127Dim0 = (int) dim0Arr[2];
double *_5317_v4130 = ((double *) argArr[0]);
double *_5313_v4122 = ((double *) argArr[1]);
double *_5316_v4127 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5317_v4130[global_id + _5317_v4130Offset] = ((- (_5313_v4122[(global_id / 30) + _5313_v4122Offset] / 30.0)) + _5316_v4127[global_id + _5316_v4127Offset]) * ((- (_5313_v4122[(global_id / 30) + _5313_v4122Offset] / 30.0)) + _5316_v4127[global_id + _5316_v4127Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
