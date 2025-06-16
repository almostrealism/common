#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation473_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6234_v4697Offset = (int) offsetArr[0];
jint _6232_v4695Offset = (int) offsetArr[1];
jint _6233_v4696Offset = (int) offsetArr[2];
jint _6234_v4697Size = (int) sizeArr[0];
jint _6232_v4695Size = (int) sizeArr[1];
jint _6233_v4696Size = (int) sizeArr[2];
jint _6234_v4697Dim0 = (int) dim0Arr[0];
jint _6232_v4695Dim0 = (int) dim0Arr[1];
jint _6233_v4696Dim0 = (int) dim0Arr[2];
double *_6234_v4697 = ((double *) argArr[0]);
double *_6232_v4695 = ((double *) argArr[1]);
double *_6233_v4696 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset] = _6232_v4695[(((global_id * 16) % 16) + (global_id * _6232_v4695Dim0)) + _6232_v4695Offset] * _6233_v4696[(((global_id * 16) % 16) + (global_id * _6233_v4696Dim0)) + _6233_v4696Offset];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 1] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 1] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 1];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 2] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 2] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 2];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 3] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 3] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 3];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 4] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 4] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 4];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 5] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 5] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 5];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 6] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 6] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 6];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 7] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 7] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 7];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 8] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 8] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 8];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 9] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 9] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 9];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 10] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 10] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 10];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 11] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 11] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 11];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 12] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 12] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 12];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 13] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 13] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 13];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 14] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 14] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 14];
_6234_v4697[(global_id * _6234_v4697Dim0) + _6234_v4697Offset + 15] = _6232_v4695[(global_id * _6232_v4695Dim0) + _6232_v4695Offset + 15] * _6233_v4696[(global_id * _6233_v4696Dim0) + _6233_v4696Offset + 15];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
