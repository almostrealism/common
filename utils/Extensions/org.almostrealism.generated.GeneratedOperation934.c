#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation934_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14219_v9326Offset = (int) offsetArr[0];
jint _14184_v9279Offset = (int) offsetArr[1];
jint _14204_v9295Offset = (int) offsetArr[2];
jint _14213_v9313Offset = (int) offsetArr[3];
jint _14219_v9326Size = (int) sizeArr[0];
jint _14184_v9279Size = (int) sizeArr[1];
jint _14204_v9295Size = (int) sizeArr[2];
jint _14213_v9313Size = (int) sizeArr[3];
jint _14219_v9326Dim0 = (int) dim0Arr[0];
jint _14184_v9279Dim0 = (int) dim0Arr[1];
jint _14204_v9295Dim0 = (int) dim0Arr[2];
jint _14213_v9313Dim0 = (int) dim0Arr[3];
double *_14219_v9326 = ((double *) argArr[0]);
double *_14184_v9279 = ((double *) argArr[1]);
double *_14204_v9295 = ((double *) argArr[2]);
double *_14213_v9313 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14219_v9326[global_id + _14219_v9326Offset] = ((((_14204_v9295[((global_id % 2) * 2) + _14204_v9295Offset + 1] + _14204_v9295[((global_id % 2) * 2) + _14204_v9295Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_14184_v9279[_14184_v9279Offset] + _14184_v9279[_14184_v9279Offset + 1]) / 2.0)) + _14184_v9279[(global_id / 2) + _14184_v9279Offset])) + ((((_14213_v9313[((global_id % 2) * 2) + _14213_v9313Offset + 1] + _14213_v9313[((global_id % 2) * 2) + _14213_v9313Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_14184_v9279[_14184_v9279Offset] + _14184_v9279[_14184_v9279Offset + 1]) / 2.0)) + _14184_v9279[(global_id / 2) + _14184_v9279Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
