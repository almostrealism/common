#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation896_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13771_v8990Offset = (int) offsetArr[0];
jint _13736_v8943Offset = (int) offsetArr[1];
jint _13756_v8959Offset = (int) offsetArr[2];
jint _13765_v8977Offset = (int) offsetArr[3];
jint _13771_v8990Size = (int) sizeArr[0];
jint _13736_v8943Size = (int) sizeArr[1];
jint _13756_v8959Size = (int) sizeArr[2];
jint _13765_v8977Size = (int) sizeArr[3];
jint _13771_v8990Dim0 = (int) dim0Arr[0];
jint _13736_v8943Dim0 = (int) dim0Arr[1];
jint _13756_v8959Dim0 = (int) dim0Arr[2];
jint _13765_v8977Dim0 = (int) dim0Arr[3];
double *_13771_v8990 = ((double *) argArr[0]);
double *_13736_v8943 = ((double *) argArr[1]);
double *_13756_v8959 = ((double *) argArr[2]);
double *_13765_v8977 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13771_v8990[global_id + _13771_v8990Offset] = ((((_13756_v8959[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13756_v8959Offset + 1] + _13756_v8959[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13756_v8959Offset + 2] + _13756_v8959[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13756_v8959Offset + 3] + _13756_v8959[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13756_v8959Offset]) * -0.25) + ((((- (global_id % 16)) + (global_id / 16)) == 0) ? 1 : 0)) * ((- ((_13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 1] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 2] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 3] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset]) / 4.0)) + _13736_v8943[(global_id / 16) + _13736_v8943Offset])) + ((((_13765_v8977[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13765_v8977Offset + 1] + _13765_v8977[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13765_v8977Offset + 2] + _13765_v8977[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13765_v8977Offset + 3] + _13765_v8977[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _13765_v8977Offset]) * -0.25) + ((((- (global_id % 16)) + (global_id / 16)) == 0) ? 1 : 0)) * ((- ((_13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 1] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 2] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset + 3] + _13736_v8943[((global_id / 64) * 4) + _13736_v8943Offset]) / 4.0)) + _13736_v8943[(global_id / 16) + _13736_v8943Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
