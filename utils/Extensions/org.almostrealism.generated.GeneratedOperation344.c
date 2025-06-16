#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation344_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4348_v3588Offset = (int) offsetArr[0];
jint _4348_v3589Offset = (int) offsetArr[1];
jint _4348_v3591Offset = (int) offsetArr[2];
jint _4348_v3588Size = (int) sizeArr[0];
jint _4348_v3589Size = (int) sizeArr[1];
jint _4348_v3591Size = (int) sizeArr[2];
jint _4348_v3588Dim0 = (int) dim0Arr[0];
jint _4348_v3589Dim0 = (int) dim0Arr[1];
jint _4348_v3591Dim0 = (int) dim0Arr[2];
double *_4348_v3588 = ((double *) argArr[0]);
double *_4348_v3589 = ((double *) argArr[1]);
double *_4348_v3591 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4348_v3588[global_id + _4348_v3588Offset] = _4348_v3589[global_id + _4348_v3589Offset] * _4348_v3591[global_id + _4348_v3591Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
