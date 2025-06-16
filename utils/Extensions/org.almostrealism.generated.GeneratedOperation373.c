#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation373_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4802_v3865Offset = (int) offsetArr[0];
jint _4802_v3866Offset = (int) offsetArr[1];
jint _4802_v3868Offset = (int) offsetArr[2];
jint _4802_v3865Size = (int) sizeArr[0];
jint _4802_v3866Size = (int) sizeArr[1];
jint _4802_v3868Size = (int) sizeArr[2];
jint _4802_v3865Dim0 = (int) dim0Arr[0];
jint _4802_v3866Dim0 = (int) dim0Arr[1];
jint _4802_v3868Dim0 = (int) dim0Arr[2];
double *_4802_v3865 = ((double *) argArr[0]);
double *_4802_v3866 = ((double *) argArr[1]);
double *_4802_v3868 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4802_v3865[global_id + _4802_v3865Offset] = _4802_v3866[global_id + _4802_v3866Offset] * _4802_v3868[global_id + _4802_v3868Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
