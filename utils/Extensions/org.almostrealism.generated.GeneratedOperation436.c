#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation436_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5625_v4349Offset = (int) offsetArr[0];
jint _5625_v4350Offset = (int) offsetArr[1];
jint _5625_v4349Size = (int) sizeArr[0];
jint _5625_v4350Size = (int) sizeArr[1];
jint _5625_v4349Dim0 = (int) dim0Arr[0];
jint _5625_v4350Dim0 = (int) dim0Arr[1];
double *_5625_v4349 = ((double *) argArr[0]);
double *_5625_v4350 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5625_v4349[_5625_v4349Offset] = 0.0;
for (int _5625_i = 0; _5625_i < 30;) {
_5625_v4349[_5625_v4349Offset] = _5625_v4350[_5625_i + _5625_v4350Offset + 90] + _5625_v4349[_5625_v4349Offset];
_5625_i = _5625_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
