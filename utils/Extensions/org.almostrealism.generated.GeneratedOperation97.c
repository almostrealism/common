#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation97_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1172_v842Offset = (int) offsetArr[0];
jint _1172_v843Offset = (int) offsetArr[1];
jint _1172_v845Offset = (int) offsetArr[2];
jint _1172_v842Size = (int) sizeArr[0];
jint _1172_v843Size = (int) sizeArr[1];
jint _1172_v845Size = (int) sizeArr[2];
jint _1172_v842Dim0 = (int) dim0Arr[0];
jint _1172_v843Dim0 = (int) dim0Arr[1];
jint _1172_v845Dim0 = (int) dim0Arr[2];
double *_1172_v842 = ((double *) argArr[0]);
double *_1172_v843 = ((double *) argArr[1]);
double *_1172_v845 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1172_v842[global_id + _1172_v842Offset] = (_1172_v845[(((global_id / 35) * 28) + (global_id % 7) + 7) + _1172_v845Offset] * _1172_v843[((global_id / 7) * 4) + _1172_v843Offset + 1]) + (_1172_v845[(((global_id / 35) * 28) + (global_id % 7) + 14) + _1172_v845Offset] * _1172_v843[((global_id / 7) * 4) + _1172_v843Offset + 2]) + (_1172_v845[(((global_id / 35) * 28) + (global_id % 7) + 21) + _1172_v845Offset] * _1172_v843[((global_id / 7) * 4) + _1172_v843Offset + 3]) + (_1172_v845[(((global_id / 35) * 28) + (global_id % 7)) + _1172_v845Offset] * _1172_v843[((global_id / 7) * 4) + _1172_v843Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
