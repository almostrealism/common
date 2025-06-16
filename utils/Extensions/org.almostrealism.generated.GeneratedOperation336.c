#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation336_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4289_v3383Offset = (int) offsetArr[0];
jint _4287_v3378Offset = (int) offsetArr[1];
jint _4287_v3379Offset = (int) offsetArr[2];
jint _4288_v3381Offset = (int) offsetArr[3];
jint _4289_v3383Size = (int) sizeArr[0];
jint _4287_v3378Size = (int) sizeArr[1];
jint _4287_v3379Size = (int) sizeArr[2];
jint _4288_v3381Size = (int) sizeArr[3];
jint _4289_v3383Dim0 = (int) dim0Arr[0];
jint _4287_v3378Dim0 = (int) dim0Arr[1];
jint _4287_v3379Dim0 = (int) dim0Arr[2];
jint _4288_v3381Dim0 = (int) dim0Arr[3];
double *_4289_v3383 = ((double *) argArr[0]);
double *_4287_v3378 = ((double *) argArr[1]);
double *_4287_v3379 = ((double *) argArr[2]);
double *_4288_v3381 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4289_v3383[global_id + _4289_v3383Offset] = (_4287_v3378[global_id + _4287_v3378Offset] + _4287_v3379[global_id + _4287_v3379Offset]) * _4288_v3381[(global_id / 4) + _4288_v3381Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
