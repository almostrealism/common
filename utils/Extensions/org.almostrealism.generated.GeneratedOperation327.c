#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation327_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4265_v3542Offset = (int) offsetArr[0];
jint _4230_v3495Offset = (int) offsetArr[1];
jint _4250_v3511Offset = (int) offsetArr[2];
jint _4259_v3529Offset = (int) offsetArr[3];
jint _4265_v3542Size = (int) sizeArr[0];
jint _4230_v3495Size = (int) sizeArr[1];
jint _4250_v3511Size = (int) sizeArr[2];
jint _4259_v3529Size = (int) sizeArr[3];
jint _4265_v3542Dim0 = (int) dim0Arr[0];
jint _4230_v3495Dim0 = (int) dim0Arr[1];
jint _4250_v3511Dim0 = (int) dim0Arr[2];
jint _4259_v3529Dim0 = (int) dim0Arr[3];
double *_4265_v3542 = ((double *) argArr[0]);
double *_4230_v3495 = ((double *) argArr[1]);
double *_4250_v3511 = ((double *) argArr[2]);
double *_4259_v3529 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4265_v3542[global_id + _4265_v3542Offset] = ((((_4250_v3511[((global_id % 4) * 4) + _4250_v3511Offset + 1] + _4250_v3511[((global_id % 4) * 4) + _4250_v3511Offset + 2] + _4250_v3511[((global_id % 4) * 4) + _4250_v3511Offset + 3] + _4250_v3511[((global_id % 4) * 4) + _4250_v3511Offset]) * -0.25) + ((((- (global_id % 4)) + (global_id / 4)) == 0) ? 1 : 0)) * ((- ((_4230_v3495[_4230_v3495Offset] + _4230_v3495[_4230_v3495Offset + 1] + _4230_v3495[_4230_v3495Offset + 2] + _4230_v3495[_4230_v3495Offset + 3]) / 4.0)) + _4230_v3495[(global_id / 4) + _4230_v3495Offset])) + ((((_4259_v3529[((global_id % 4) * 4) + _4259_v3529Offset + 1] + _4259_v3529[((global_id % 4) * 4) + _4259_v3529Offset + 2] + _4259_v3529[((global_id % 4) * 4) + _4259_v3529Offset + 3] + _4259_v3529[((global_id % 4) * 4) + _4259_v3529Offset]) * -0.25) + ((((- (global_id % 4)) + (global_id / 4)) == 0) ? 1 : 0)) * ((- ((_4230_v3495[_4230_v3495Offset] + _4230_v3495[_4230_v3495Offset + 1] + _4230_v3495[_4230_v3495Offset + 2] + _4230_v3495[_4230_v3495Offset + 3]) / 4.0)) + _4230_v3495[(global_id / 4) + _4230_v3495Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
