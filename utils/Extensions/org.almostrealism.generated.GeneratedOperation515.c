#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation515_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7156_v5217Offset = (int) offsetArr[0];
jint _7121_v5170Offset = (int) offsetArr[1];
jint _7141_v5186Offset = (int) offsetArr[2];
jint _7150_v5204Offset = (int) offsetArr[3];
jint _7156_v5217Size = (int) sizeArr[0];
jint _7121_v5170Size = (int) sizeArr[1];
jint _7141_v5186Size = (int) sizeArr[2];
jint _7150_v5204Size = (int) sizeArr[3];
jint _7156_v5217Dim0 = (int) dim0Arr[0];
jint _7121_v5170Dim0 = (int) dim0Arr[1];
jint _7141_v5186Dim0 = (int) dim0Arr[2];
jint _7150_v5204Dim0 = (int) dim0Arr[3];
double *_7156_v5217 = ((double *) argArr[0]);
double *_7121_v5170 = ((double *) argArr[1]);
double *_7141_v5186 = ((double *) argArr[2]);
double *_7150_v5204 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7156_v5217[global_id + _7156_v5217Offset] = ((((_7141_v5186[((global_id % 2) * 2) + _7141_v5186Offset + 1] + _7141_v5186[((global_id % 2) * 2) + _7141_v5186Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_7121_v5170[_7121_v5170Offset] + _7121_v5170[_7121_v5170Offset + 1]) / 2.0)) + _7121_v5170[(global_id / 2) + _7121_v5170Offset])) + ((((_7150_v5204[((global_id % 2) * 2) + _7150_v5204Offset + 1] + _7150_v5204[((global_id % 2) * 2) + _7150_v5204Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_7121_v5170[_7121_v5170Offset] + _7121_v5170[_7121_v5170Offset + 1]) / 2.0)) + _7121_v5170[(global_id / 2) + _7121_v5170Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
