#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation389_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5286_v3928Offset = (int) offsetArr[0];
jint _5236_v3916Offset = (int) offsetArr[1];
jint _5239_v3921Offset = (int) offsetArr[2];
jint _5285_v3927Offset = (int) offsetArr[3];
jint _5286_v3928Size = (int) sizeArr[0];
jint _5236_v3916Size = (int) sizeArr[1];
jint _5239_v3921Size = (int) sizeArr[2];
jint _5285_v3927Size = (int) sizeArr[3];
jint _5286_v3928Dim0 = (int) dim0Arr[0];
jint _5236_v3916Dim0 = (int) dim0Arr[1];
jint _5239_v3921Dim0 = (int) dim0Arr[2];
jint _5285_v3927Dim0 = (int) dim0Arr[3];
double *_5286_v3928 = ((double *) argArr[0]);
double *_5236_v3916 = ((double *) argArr[1]);
double *_5239_v3921 = ((double *) argArr[2]);
double *_5285_v3927 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5286_v3928[global_id + _5286_v3928Offset] = ((- (_5236_v3916[(global_id / 3600) + _5236_v3916Offset] / 30.0)) + _5239_v3921[(global_id / 120) + _5239_v3921Offset]) * _5285_v3927[(((global_id / 3600) * 120) + (global_id % 120)) + _5285_v3927Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
