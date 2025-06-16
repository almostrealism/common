#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation101_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1182_v874Offset = (int) offsetArr[0];
jint _1181_v872Offset = (int) offsetArr[1];
jint _1182_v875Offset = (int) offsetArr[2];
jint _1182_v874Size = (int) sizeArr[0];
jint _1181_v872Size = (int) sizeArr[1];
jint _1182_v875Size = (int) sizeArr[2];
jint _1182_v874Dim0 = (int) dim0Arr[0];
jint _1181_v872Dim0 = (int) dim0Arr[1];
jint _1182_v875Dim0 = (int) dim0Arr[2];
double *_1182_v874 = ((double *) argArr[0]);
double *_1181_v872 = ((double *) argArr[1]);
double *_1182_v875 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1182_v874[global_id + _1182_v874Offset] = (_1181_v872[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 1) + _1181_v872Offset] * _1182_v875[((global_id / 6) * 4) + _1182_v875Offset + 1]) + (_1181_v872[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 2) + _1181_v872Offset] * _1182_v875[((global_id / 6) * 4) + _1182_v875Offset + 2]) + (_1181_v872[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 3) + _1181_v872Offset] * _1182_v875[((global_id / 6) * 4) + _1182_v875Offset + 3]) + (_1181_v872[(((global_id % 6) * 4) + ((global_id / 36) * 24)) + _1181_v872Offset] * _1182_v875[((global_id / 6) * 4) + _1182_v875Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
