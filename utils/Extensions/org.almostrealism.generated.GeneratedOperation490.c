#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation490_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6506_v4862Offset = (int) offsetArr[0];
jint _6501_v4851Offset = (int) offsetArr[1];
jint _6506_v4862Size = (int) sizeArr[0];
jint _6501_v4851Size = (int) sizeArr[1];
jint _6506_v4862Dim0 = (int) dim0Arr[0];
jint _6501_v4851Dim0 = (int) dim0Arr[1];
double *_6506_v4862 = ((double *) argArr[0]);
double *_6501_v4851 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6506_v4862[global_id + _6506_v4862Offset] = ((- ((_6501_v4851[_6501_v4851Offset + 53] + _6501_v4851[_6501_v4851Offset + 54] + _6501_v4851[_6501_v4851Offset + 58] + _6501_v4851[_6501_v4851Offset + 55] + _6501_v4851[_6501_v4851Offset + 61] + _6501_v4851[_6501_v4851Offset + 57] + _6501_v4851[_6501_v4851Offset + 56] + _6501_v4851[_6501_v4851Offset + 63] + _6501_v4851[_6501_v4851Offset + 48] + _6501_v4851[_6501_v4851Offset + 49] + _6501_v4851[_6501_v4851Offset + 60] + _6501_v4851[_6501_v4851Offset + 50] + _6501_v4851[_6501_v4851Offset + 51] + _6501_v4851[_6501_v4851Offset + 52] + _6501_v4851[_6501_v4851Offset + 62] + _6501_v4851[_6501_v4851Offset + 59]) / 16.0)) + _6501_v4851[global_id + _6501_v4851Offset + 48]) * ((- ((_6501_v4851[_6501_v4851Offset + 53] + _6501_v4851[_6501_v4851Offset + 54] + _6501_v4851[_6501_v4851Offset + 58] + _6501_v4851[_6501_v4851Offset + 55] + _6501_v4851[_6501_v4851Offset + 61] + _6501_v4851[_6501_v4851Offset + 57] + _6501_v4851[_6501_v4851Offset + 56] + _6501_v4851[_6501_v4851Offset + 63] + _6501_v4851[_6501_v4851Offset + 48] + _6501_v4851[_6501_v4851Offset + 49] + _6501_v4851[_6501_v4851Offset + 60] + _6501_v4851[_6501_v4851Offset + 50] + _6501_v4851[_6501_v4851Offset + 51] + _6501_v4851[_6501_v4851Offset + 52] + _6501_v4851[_6501_v4851Offset + 62] + _6501_v4851[_6501_v4851Offset + 59]) / 16.0)) + _6501_v4851[global_id + _6501_v4851Offset + 48]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
