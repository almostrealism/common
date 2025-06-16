#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation647_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10096_v6626Offset = (int) offsetArr[0];
jint _10091_v6615Offset = (int) offsetArr[1];
jint _10096_v6626Size = (int) sizeArr[0];
jint _10091_v6615Size = (int) sizeArr[1];
jint _10096_v6626Dim0 = (int) dim0Arr[0];
jint _10091_v6615Dim0 = (int) dim0Arr[1];
double *_10096_v6626 = ((double *) argArr[0]);
double *_10091_v6615 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10096_v6626[global_id + _10096_v6626Offset] = ((- ((_10091_v6615[_10091_v6615Offset] + _10091_v6615[_10091_v6615Offset + 1] + _10091_v6615[_10091_v6615Offset + 2] + _10091_v6615[_10091_v6615Offset + 3]) / 4.0)) + _10091_v6615[global_id + _10091_v6615Offset]) * ((- ((_10091_v6615[_10091_v6615Offset] + _10091_v6615[_10091_v6615Offset + 1] + _10091_v6615[_10091_v6615Offset + 2] + _10091_v6615[_10091_v6615Offset + 3]) / 4.0)) + _10091_v6615[global_id + _10091_v6615Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
