#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation145_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1938_v1897Offset = (int) offsetArr[0];
jint _1938_v1898Offset = (int) offsetArr[1];
jint _1938_v1897Size = (int) sizeArr[0];
jint _1938_v1898Size = (int) sizeArr[1];
jint _1938_v1897Dim0 = (int) dim0Arr[0];
jint _1938_v1898Dim0 = (int) dim0Arr[1];
double *_1938_v1897 = ((double *) argArr[0]);
double *_1938_v1898 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
for (int _1938_i = 0; _1938_i < 7; _1938_i++) {
    if (_1938_i < 4) {
        _1938_v1897[((global_id * _1938_v1897Dim0) + (_1938_i * 2)) + _1938_v1897Offset] = _1938_v1898[((global_id * _1938_v1898Dim0) + (_1938_i * 2)) + _1938_v1898Offset];
        _1938_v1897[(((_1938_i * 2) + 1) + (global_id * _1938_v1897Dim0)) + _1938_v1897Offset] = _1938_v1898[(((_1938_i * 2) + 1) + (global_id * _1938_v1898Dim0)) + _1938_v1898Offset];
    } else {
        _1938_v1897[((global_id * _1938_v1897Dim0) + (_1938_i * 2)) + _1938_v1897Offset] = 0.0;
        _1938_v1897[(((_1938_i * 2) + 1) + (global_id * _1938_v1897Dim0)) + _1938_v1897Offset] = 1.0;
    }
}

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
