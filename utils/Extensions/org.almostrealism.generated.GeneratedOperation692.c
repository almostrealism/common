#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation692_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10867_v7113Offset = (int) offsetArr[0];
jint _10862_v7102Offset = (int) offsetArr[1];
jint _10867_v7113Size = (int) sizeArr[0];
jint _10862_v7102Size = (int) sizeArr[1];
jint _10867_v7113Dim0 = (int) dim0Arr[0];
jint _10862_v7102Dim0 = (int) dim0Arr[1];
double *_10867_v7113 = ((double *) argArr[0]);
double *_10862_v7102 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10867_v7113[global_id + _10867_v7113Offset] = ((- ((_10862_v7102[_10862_v7102Offset] + _10862_v7102[_10862_v7102Offset + 1]) / 2.0)) + _10862_v7102[global_id + _10862_v7102Offset]) * ((- ((_10862_v7102[_10862_v7102Offset] + _10862_v7102[_10862_v7102Offset + 1]) / 2.0)) + _10862_v7102[global_id + _10862_v7102Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
