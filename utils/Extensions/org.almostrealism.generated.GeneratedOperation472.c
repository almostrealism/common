#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation472_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6226_v4692Offset = (int) offsetArr[0];
jint _6221_v4684Offset = (int) offsetArr[1];
jint _6222_v4685Offset = (int) offsetArr[2];
jint _6225_v4691Offset = (int) offsetArr[3];
jint _6226_v4692Size = (int) sizeArr[0];
jint _6221_v4684Size = (int) sizeArr[1];
jint _6222_v4685Size = (int) sizeArr[2];
jint _6225_v4691Size = (int) sizeArr[3];
jint _6226_v4692Dim0 = (int) dim0Arr[0];
jint _6221_v4684Dim0 = (int) dim0Arr[1];
jint _6222_v4685Dim0 = (int) dim0Arr[2];
jint _6225_v4691Dim0 = (int) dim0Arr[3];
double *_6226_v4692 = ((double *) argArr[0]);
double *_6221_v4684 = ((double *) argArr[1]);
double *_6222_v4685 = ((double *) argArr[2]);
double *_6225_v4691 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6226_v4692[(global_id * _6226_v4692Dim0) + _6226_v4692Offset] = ((- _6222_v4685[(((global_id / _6222_v4685Size) * _6222_v4685Dim0) + (global_id % _6222_v4685Size)) + _6222_v4685Offset]) + _6221_v4684[(((global_id / 16) * _6221_v4684Dim0) + (global_id % 16)) + _6221_v4684Offset]) / _6225_v4691[(((global_id / _6225_v4691Size) * _6225_v4691Dim0) + (global_id % _6225_v4691Size)) + _6225_v4691Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
