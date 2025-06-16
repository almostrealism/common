#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation461_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6193_v4650Offset = (int) offsetArr[0];
jint _6188_v4639Offset = (int) offsetArr[1];
jint _6193_v4650Size = (int) sizeArr[0];
jint _6188_v4639Size = (int) sizeArr[1];
jint _6193_v4650Dim0 = (int) dim0Arr[0];
jint _6188_v4639Dim0 = (int) dim0Arr[1];
double *_6193_v4650 = ((double *) argArr[0]);
double *_6188_v4639 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6193_v4650[global_id + _6193_v4650Offset] = ((- ((_6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 8] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 1] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 13] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 2] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 3] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 4] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 15] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 12] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 5] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 6] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 11] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 7] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 14] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 10] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 9] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset]) / 16.0)) + _6188_v4639[global_id + _6188_v4639Offset]) * ((- ((_6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 8] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 1] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 13] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 2] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 3] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 4] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 15] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 12] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 5] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 6] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 11] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 7] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 14] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 10] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset + 9] + _6188_v4639[((global_id / 16) * 16) + _6188_v4639Offset]) / 16.0)) + _6188_v4639[global_id + _6188_v4639Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
