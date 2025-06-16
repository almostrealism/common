#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation108_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1201_v917Offset = (int) offsetArr[0];
jint _1201_v918Offset = (int) offsetArr[1];
jint _1201_v920Offset = (int) offsetArr[2];
jint _1201_v917Size = (int) sizeArr[0];
jint _1201_v918Size = (int) sizeArr[1];
jint _1201_v920Size = (int) sizeArr[2];
jint _1201_v917Dim0 = (int) dim0Arr[0];
jint _1201_v918Dim0 = (int) dim0Arr[1];
jint _1201_v920Dim0 = (int) dim0Arr[2];
double *_1201_v917 = ((double *) argArr[0]);
double *_1201_v918 = ((double *) argArr[1]);
double *_1201_v920 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1201_v917[global_id + _1201_v917Offset] = ((global_id % 2) == 1) ? ((_1201_v920[((global_id / 2) * 2) + _1201_v920Offset + 1] * _1201_v918[((global_id / 2) * 2) + _1201_v918Offset]) + (_1201_v918[((global_id / 2) * 2) + _1201_v918Offset + 1] * _1201_v920[((global_id / 2) * 2) + _1201_v920Offset])) : ((- (_1201_v918[((global_id / 2) * 2) + _1201_v918Offset + 1] * _1201_v920[((global_id / 2) * 2) + _1201_v920Offset + 1])) + (_1201_v918[((global_id / 2) * 2) + _1201_v918Offset] * _1201_v920[((global_id / 2) * 2) + _1201_v920Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
