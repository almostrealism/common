#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation147_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1986_v1946Offset = (int) offsetArr[0];
jint _1990_v1950Offset = (int) offsetArr[1];
jint _1986_v1946Size = (int) sizeArr[0];
jint _1990_v1950Size = (int) sizeArr[1];
jint _1986_v1946Dim0 = (int) dim0Arr[0];
jint _1990_v1950Dim0 = (int) dim0Arr[1];
double *_1986_v1946 = ((double *) argArr[0]);
double *_1990_v1950 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1986_v1946[_1986_v1946Offset] = 0.4;
_1986_v1946[_1986_v1946Offset + 1] = 1.0;
if (_1986_v1946[_1986_v1946Offset] <= 0.3333333333333333) {
_1990_v1950[_1990_v1950Offset] = 2.0;
}
else if (_1986_v1946[_1986_v1946Offset] <= 0.6666666666666666) {
_1990_v1950[_1990_v1950Offset] = 4.0;
}
else if (_1986_v1946[_1986_v1946Offset] <= 1.0) {
_1990_v1950[_1990_v1950Offset] = 8.0;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
