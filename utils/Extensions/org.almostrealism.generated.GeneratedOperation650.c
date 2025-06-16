#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation650_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10064_v6642Offset = (int) offsetArr[0];
jint _10062_v6640Offset = (int) offsetArr[1];
jint _10063_v6641Offset = (int) offsetArr[2];
jint _10064_v6642Size = (int) sizeArr[0];
jint _10062_v6640Size = (int) sizeArr[1];
jint _10063_v6641Size = (int) sizeArr[2];
jint _10064_v6642Dim0 = (int) dim0Arr[0];
jint _10062_v6640Dim0 = (int) dim0Arr[1];
jint _10063_v6641Dim0 = (int) dim0Arr[2];
double *_10064_v6642 = ((double *) argArr[0]);
double *_10062_v6640 = ((double *) argArr[1]);
double *_10063_v6641 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10064_v6642[(global_id * _10064_v6642Dim0) + _10064_v6642Offset] = _10062_v6640[(((global_id * 4) % 4) + (global_id * _10062_v6640Dim0)) + _10062_v6640Offset] * _10063_v6641[(((global_id * 4) % 4) + (global_id * _10063_v6641Dim0)) + _10063_v6641Offset];
_10064_v6642[(global_id * _10064_v6642Dim0) + _10064_v6642Offset + 1] = _10062_v6640[(global_id * _10062_v6640Dim0) + _10062_v6640Offset + 1] * _10063_v6641[(global_id * _10063_v6641Dim0) + _10063_v6641Offset + 1];
_10064_v6642[(global_id * _10064_v6642Dim0) + _10064_v6642Offset + 2] = _10062_v6640[(global_id * _10062_v6640Dim0) + _10062_v6640Offset + 2] * _10063_v6641[(global_id * _10063_v6641Dim0) + _10063_v6641Offset + 2];
_10064_v6642[(global_id * _10064_v6642Dim0) + _10064_v6642Offset + 3] = _10062_v6640[(global_id * _10062_v6640Dim0) + _10062_v6640Offset + 3] * _10063_v6641[(global_id * _10063_v6641Dim0) + _10063_v6641Offset + 3];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
