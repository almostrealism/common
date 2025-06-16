#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation301_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3864_v3298Offset = (int) offsetArr[0];
jint _3829_v3251Offset = (int) offsetArr[1];
jint _3849_v3267Offset = (int) offsetArr[2];
jint _3858_v3285Offset = (int) offsetArr[3];
jint _3864_v3298Size = (int) sizeArr[0];
jint _3829_v3251Size = (int) sizeArr[1];
jint _3849_v3267Size = (int) sizeArr[2];
jint _3858_v3285Size = (int) sizeArr[3];
jint _3864_v3298Dim0 = (int) dim0Arr[0];
jint _3829_v3251Dim0 = (int) dim0Arr[1];
jint _3849_v3267Dim0 = (int) dim0Arr[2];
jint _3858_v3285Dim0 = (int) dim0Arr[3];
double *_3864_v3298 = ((double *) argArr[0]);
double *_3829_v3251 = ((double *) argArr[1]);
double *_3849_v3267 = ((double *) argArr[2]);
double *_3858_v3285 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3864_v3298[global_id + _3864_v3298Offset] = ((((_3849_v3267[((global_id % 2) * 2) + _3849_v3267Offset + 1] + _3849_v3267[((global_id % 2) * 2) + _3849_v3267Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_3829_v3251[_3829_v3251Offset] + _3829_v3251[_3829_v3251Offset + 1]) / 2.0)) + _3829_v3251[(global_id / 2) + _3829_v3251Offset])) + ((((_3858_v3285[((global_id % 2) * 2) + _3858_v3285Offset + 1] + _3858_v3285[((global_id % 2) * 2) + _3858_v3285Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_3829_v3251[_3829_v3251Offset] + _3829_v3251[_3829_v3251Offset + 1]) / 2.0)) + _3829_v3251[(global_id / 2) + _3829_v3251Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
