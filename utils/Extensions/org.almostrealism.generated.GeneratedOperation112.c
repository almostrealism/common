#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation112_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1239_v938Offset = (int) offsetArr[0];
jint _1239_v940Offset = (int) offsetArr[1];
jint _1239_v938Size = (int) sizeArr[0];
jint _1239_v940Size = (int) sizeArr[1];
jint _1239_v938Dim0 = (int) dim0Arr[0];
jint _1239_v940Dim0 = (int) dim0Arr[1];
double *_1239_v938 = ((double *) argArr[0]);
double *_1239_v940 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset] = (((((global_id * 9) % 9) / 3) + (- ((global_id * 9) % 3))) == 0) ? _1239_v940[((global_id * 9) % 3) + _1239_v940Offset] : 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 1] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 2] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 3] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 4] = _1239_v940[_1239_v940Offset + 1];
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 5] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 6] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 7] = 0;
_1239_v938[(global_id * _1239_v938Dim0) + _1239_v938Offset + 8] = _1239_v940[_1239_v940Offset + 2];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
