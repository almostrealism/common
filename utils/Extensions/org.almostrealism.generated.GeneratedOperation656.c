#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation656_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10178_v6683Offset = (int) offsetArr[0];
jint _10177_v6681Offset = (int) offsetArr[1];
jint _10178_v6683Size = (int) sizeArr[0];
jint _10177_v6681Size = (int) sizeArr[1];
jint _10178_v6683Dim0 = (int) dim0Arr[0];
jint _10177_v6681Dim0 = (int) dim0Arr[1];
double *_10178_v6683 = ((double *) argArr[0]);
double *_10177_v6681 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10178_v6683[_10178_v6683Offset] = (_10177_v6681[_10177_v6681Offset] + _10177_v6681[_10177_v6681Offset + 1] + _10177_v6681[_10177_v6681Offset + 2] + _10177_v6681[_10177_v6681Offset + 3]) / 4.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
