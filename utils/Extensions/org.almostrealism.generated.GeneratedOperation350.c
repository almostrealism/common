#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation350_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4502_v3628Offset = (int) offsetArr[0];
jint _4484_v3603Offset = (int) offsetArr[1];
jint _4487_v3608Offset = (int) offsetArr[2];
jint _4495_v3613Offset = (int) offsetArr[3];
jint _4501_v3626Offset = (int) offsetArr[4];
jint _4502_v3630Offset = (int) offsetArr[5];
jint _4502_v3628Size = (int) sizeArr[0];
jint _4484_v3603Size = (int) sizeArr[1];
jint _4487_v3608Size = (int) sizeArr[2];
jint _4495_v3613Size = (int) sizeArr[3];
jint _4501_v3626Size = (int) sizeArr[4];
jint _4502_v3630Size = (int) sizeArr[5];
jint _4502_v3628Dim0 = (int) dim0Arr[0];
jint _4484_v3603Dim0 = (int) dim0Arr[1];
jint _4487_v3608Dim0 = (int) dim0Arr[2];
jint _4495_v3613Dim0 = (int) dim0Arr[3];
jint _4501_v3626Dim0 = (int) dim0Arr[4];
jint _4502_v3630Dim0 = (int) dim0Arr[5];
double *_4502_v3628 = ((double *) argArr[0]);
double *_4484_v3603 = ((double *) argArr[1]);
double *_4487_v3608 = ((double *) argArr[2]);
double *_4495_v3613 = ((double *) argArr[3]);
double *_4501_v3626 = ((double *) argArr[4]);
double *_4502_v3630 = ((double *) argArr[5]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4502_v3628[global_id + _4502_v3628Offset] = ((((- (_4484_v3603[(global_id / 5488) + _4484_v3603Offset] / 5488.0)) + _4487_v3608[global_id + _4487_v3608Offset]) / pow((_4495_v3613[(global_id / 5488) + _4495_v3613Offset] / 5488.0) + 1.0E-5, 0.5)) * _4501_v3626[global_id + _4501_v3626Offset]) + _4502_v3630[global_id + _4502_v3630Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
