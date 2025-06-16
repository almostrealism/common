#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation106_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1199_v909Offset = (int) offsetArr[0];
jint _1199_v910Offset = (int) offsetArr[1];
jint _1199_v912Offset = (int) offsetArr[2];
jint _1199_v909Size = (int) sizeArr[0];
jint _1199_v910Size = (int) sizeArr[1];
jint _1199_v912Size = (int) sizeArr[2];
jint _1199_v909Dim0 = (int) dim0Arr[0];
jint _1199_v910Dim0 = (int) dim0Arr[1];
jint _1199_v912Dim0 = (int) dim0Arr[2];
double *_1199_v909 = ((double *) argArr[0]);
double *_1199_v910 = ((double *) argArr[1]);
double *_1199_v912 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1199_v909[global_id + _1199_v909Offset] = ((global_id % 2) == 1) ? ((_1199_v912[((((global_id / 2) * 2) + 1) % 64) + _1199_v912Offset] * _1199_v910[((global_id / 2) * 2) + _1199_v910Offset]) + (_1199_v910[((global_id / 2) * 2) + _1199_v910Offset + 1] * _1199_v912[(((global_id / 2) * 2) % 64) + _1199_v912Offset])) : ((- (_1199_v912[((((global_id / 2) * 2) + 1) % 64) + _1199_v912Offset] * _1199_v910[((global_id / 2) * 2) + _1199_v910Offset + 1])) + (_1199_v912[(((global_id / 2) * 2) % 64) + _1199_v912Offset] * _1199_v910[((global_id / 2) * 2) + _1199_v910Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
