#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation309_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3885_v3192Offset = (int) offsetArr[0];
jint _3835_v3162Offset = (int) offsetArr[1];
jint _3880_v3182Offset = (int) offsetArr[2];
jint _3885_v3192Size = (int) sizeArr[0];
jint _3835_v3162Size = (int) sizeArr[1];
jint _3880_v3182Size = (int) sizeArr[2];
jint _3885_v3192Dim0 = (int) dim0Arr[0];
jint _3835_v3162Dim0 = (int) dim0Arr[1];
jint _3880_v3182Dim0 = (int) dim0Arr[2];
double *_3885_v3192 = ((double *) argArr[0]);
double *_3835_v3162 = ((double *) argArr[1]);
double *_3880_v3182 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3885_v3192[global_id + _3885_v3192Offset] = (((_3880_v3182[((global_id % 2) * 2) + _3880_v3182Offset + 1] + _3880_v3182[((global_id % 2) * 2) + _3880_v3182Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * pow(pow(((_3835_v3162[_3835_v3162Offset] + _3835_v3162[_3835_v3162Offset + 1]) / 2.0) + 1.0E-5, 0.5), -1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
