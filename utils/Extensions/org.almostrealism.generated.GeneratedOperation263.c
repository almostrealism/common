#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation263_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3133_v2863Offset = (int) offsetArr[0];
jint _3133_v2864Offset = (int) offsetArr[1];
jint _3133_v2863Size = (int) sizeArr[0];
jint _3133_v2864Size = (int) sizeArr[1];
jint _3133_v2863Dim0 = (int) dim0Arr[0];
jint _3133_v2864Dim0 = (int) dim0Arr[1];
double *_3133_v2863 = ((double *) argArr[0]);
double *_3133_v2864 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3133_v2863[(global_id * _3133_v2863Dim0) + _3133_v2863Offset] = 0.0;
for (int _3133_i = 0; _3133_i < 30;) {
jint k_3133_i = (global_id * 30) + _3133_i;
_3133_v2863[(global_id * _3133_v2863Dim0) + _3133_v2863Offset] = _3133_v2864[(k_3133_i) + _3133_v2864Offset] + _3133_v2863[(global_id * _3133_v2863Dim0) + _3133_v2863Offset];
_3133_i = _3133_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
