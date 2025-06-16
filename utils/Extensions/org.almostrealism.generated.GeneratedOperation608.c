#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation608_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9322_v6203Offset = (int) offsetArr[0];
jint _9303_v6188Offset = (int) offsetArr[1];
jint _9322_v6203Size = (int) sizeArr[0];
jint _9303_v6188Size = (int) sizeArr[1];
jint _9322_v6203Dim0 = (int) dim0Arr[0];
jint _9303_v6188Dim0 = (int) dim0Arr[1];
double *_9322_v6203 = ((double *) argArr[0]);
double *_9303_v6188 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9322_v6203[_9322_v6203Offset] = ((((- ((_9303_v6188[_9303_v6188Offset + 2] + _9303_v6188[_9303_v6188Offset + 3]) / 2.0)) + _9303_v6188[_9303_v6188Offset + 2]) * ((- ((_9303_v6188[_9303_v6188Offset + 2] + _9303_v6188[_9303_v6188Offset + 3]) / 2.0)) + _9303_v6188[_9303_v6188Offset + 2])) + (((- ((_9303_v6188[_9303_v6188Offset + 2] + _9303_v6188[_9303_v6188Offset + 3]) / 2.0)) + _9303_v6188[_9303_v6188Offset + 3]) * ((- ((_9303_v6188[_9303_v6188Offset + 2] + _9303_v6188[_9303_v6188Offset + 3]) / 2.0)) + _9303_v6188[_9303_v6188Offset + 3]))) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
