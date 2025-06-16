#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation89_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1151_v808Offset = (int) offsetArr[0];
jint _1131_v791Offset = (int) offsetArr[1];
jint _1140_v789Offset = (int) offsetArr[2];
jint _1151_v808Size = (int) sizeArr[0];
jint _1131_v791Size = (int) sizeArr[1];
jint _1140_v789Size = (int) sizeArr[2];
jint _1151_v808Dim0 = (int) dim0Arr[0];
jint _1131_v791Dim0 = (int) dim0Arr[1];
jint _1140_v789Dim0 = (int) dim0Arr[2];
double *_1151_v808 = ((double *) argArr[0]);
double *_1131_v791 = ((double *) argArr[1]);
double *_1140_v789 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset] = _1140_v789[(((((int) ((((_1131_v791[((((global_id * 8) / 2) % 4) + (global_id * _1131_v791Dim0)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + ((((global_id * 8) % 2) == 1) ? 1.0 : 0))) / 20) * _1140_v789Dim0) + (((int) ((((_1131_v791[((((global_id * 8) / 2) % 4) + (global_id * _1131_v791Dim0)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + ((((global_id * 8) % 2) == 1) ? 1.0 : 0))) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 1] = _1140_v789[(((((int) ((((_1131_v791[((((global_id * 8) / 2) % 4) + (global_id * _1131_v791Dim0)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) / 20) * _1140_v789Dim0) + (((int) ((((_1131_v791[((((global_id * 8) / 2) % 4) + (global_id * _1131_v791Dim0)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 2] = _1140_v789[(((((int) (((_1131_v791[((((((global_id * 8) / 2) + 1) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 1) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) / 20) * _1140_v789Dim0) + (((int) (((_1131_v791[((((((global_id * 8) / 2) + 1) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 1) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 3] = _1140_v789[(((((int) ((((_1131_v791[((((((global_id * 8) / 2) + 1) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 1) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) / 20) * _1140_v789Dim0) + (((int) ((((_1131_v791[((((((global_id * 8) / 2) + 1) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 1) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 4] = _1140_v789[(((((int) (((_1131_v791[((((((global_id * 8) / 2) + 2) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 2) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) / 20) * _1140_v789Dim0) + (((int) (((_1131_v791[((((((global_id * 8) / 2) + 2) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 2) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 5] = _1140_v789[(((((int) ((((_1131_v791[((((((global_id * 8) / 2) + 2) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 2) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) / 20) * _1140_v789Dim0) + (((int) ((((_1131_v791[((((((global_id * 8) / 2) + 2) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 2) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 6] = _1140_v789[(((((int) (((_1131_v791[((((((global_id * 8) / 2) + 3) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 3) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) / 20) * _1140_v789Dim0) + (((int) (((_1131_v791[((((((global_id * 8) / 2) + 3) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 3) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0)) % 20)) + _1140_v789Offset];
_1151_v808[(global_id * _1151_v808Dim0) + _1151_v808Offset + 7] = _1140_v789[(((((int) ((((_1131_v791[((((((global_id * 8) / 2) + 3) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 3) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) / 20) * _1140_v789Dim0) + (((int) ((((_1131_v791[((((((global_id * 8) / 2) + 3) / 4) * _1131_v791Dim0) + ((((global_id * 8) / 2) + 3) % 4)) + _1131_v791Offset] * 2.0) + 1.0) * 2.0) + 1.0)) % 20)) + _1140_v789Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
