#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation457_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6177_v4428Offset = (int) offsetArr[0];
jint _6110_v4417Offset = (int) offsetArr[1];
jint _6174_v4420Offset = (int) offsetArr[2];
jint _6174_v4421Offset = (int) offsetArr[3];
jint _6176_v4426Offset = (int) offsetArr[4];
jint _6177_v4428Size = (int) sizeArr[0];
jint _6110_v4417Size = (int) sizeArr[1];
jint _6174_v4420Size = (int) sizeArr[2];
jint _6174_v4421Size = (int) sizeArr[3];
jint _6176_v4426Size = (int) sizeArr[4];
jint _6177_v4428Dim0 = (int) dim0Arr[0];
jint _6110_v4417Dim0 = (int) dim0Arr[1];
jint _6174_v4420Dim0 = (int) dim0Arr[2];
jint _6174_v4421Dim0 = (int) dim0Arr[3];
jint _6176_v4426Dim0 = (int) dim0Arr[4];
double *_6177_v4428 = ((double *) argArr[0]);
double *_6110_v4417 = ((double *) argArr[1]);
double *_6174_v4420 = ((double *) argArr[2]);
double *_6174_v4421 = ((double *) argArr[3]);
double *_6176_v4426 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6177_v4428[global_id + _6177_v4428Offset] = (_6110_v4417[(global_id / 96) + _6110_v4417Offset] * (_6174_v4420[global_id + _6174_v4420Offset] + _6174_v4421[global_id + _6174_v4421Offset])) * _6176_v4426[(global_id / 96) + _6176_v4426Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
