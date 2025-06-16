#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation359_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4729_v3828Offset = (int) offsetArr[0];
jint _4694_v3781Offset = (int) offsetArr[1];
jint _4714_v3797Offset = (int) offsetArr[2];
jint _4723_v3815Offset = (int) offsetArr[3];
jint _4729_v3828Size = (int) sizeArr[0];
jint _4694_v3781Size = (int) sizeArr[1];
jint _4714_v3797Size = (int) sizeArr[2];
jint _4723_v3815Size = (int) sizeArr[3];
jint _4729_v3828Dim0 = (int) dim0Arr[0];
jint _4694_v3781Dim0 = (int) dim0Arr[1];
jint _4714_v3797Dim0 = (int) dim0Arr[2];
jint _4723_v3815Dim0 = (int) dim0Arr[3];
double *_4729_v3828 = ((double *) argArr[0]);
double *_4694_v3781 = ((double *) argArr[1]);
double *_4714_v3797 = ((double *) argArr[2]);
double *_4723_v3815 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4729_v3828[global_id + _4729_v3828Offset] = ((((_4714_v3797[((global_id % 2) * 2) + _4714_v3797Offset + 1] + _4714_v3797[((global_id % 2) * 2) + _4714_v3797Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_4694_v3781[_4694_v3781Offset] + _4694_v3781[_4694_v3781Offset + 1]) / 2.0)) + _4694_v3781[(global_id / 2) + _4694_v3781Offset])) + ((((_4723_v3815[((global_id % 2) * 2) + _4723_v3815Offset + 1] + _4723_v3815[((global_id % 2) * 2) + _4723_v3815Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_4694_v3781[_4694_v3781Offset] + _4694_v3781[_4694_v3781Offset + 1]) / 2.0)) + _4694_v3781[(global_id / 2) + _4694_v3781Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
