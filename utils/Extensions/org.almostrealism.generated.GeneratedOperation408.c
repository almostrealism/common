#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation408_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5340_v4136Offset = (int) offsetArr[0];
jint _5340_v4137Offset = (int) offsetArr[1];
jint _5340_v4136Size = (int) sizeArr[0];
jint _5340_v4137Size = (int) sizeArr[1];
jint _5340_v4136Dim0 = (int) dim0Arr[0];
jint _5340_v4137Dim0 = (int) dim0Arr[1];
double *_5340_v4136 = ((double *) argArr[0]);
double *_5340_v4137 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5340_v4136[(global_id * _5340_v4136Dim0) + _5340_v4136Offset] = 0.0;
for (int _5340_i = 0; _5340_i < 120;) {
jint k_5340_i = (global_id * 120) + _5340_i;
_5340_v4136[(global_id * _5340_v4136Dim0) + _5340_v4136Offset] = _5340_v4137[(k_5340_i) + _5340_v4137Offset] + _5340_v4136[(global_id * _5340_v4136Dim0) + _5340_v4136Offset];
_5340_i = _5340_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
