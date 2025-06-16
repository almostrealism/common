#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation798_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12145_v7860Offset = (int) offsetArr[0];
jint _12096_v7835Offset = (int) offsetArr[1];
jint _12141_v7852Offset = (int) offsetArr[2];
jint _12145_v7860Size = (int) sizeArr[0];
jint _12096_v7835Size = (int) sizeArr[1];
jint _12141_v7852Size = (int) sizeArr[2];
jint _12145_v7860Dim0 = (int) dim0Arr[0];
jint _12096_v7835Dim0 = (int) dim0Arr[1];
jint _12141_v7852Dim0 = (int) dim0Arr[2];
double *_12145_v7860 = ((double *) argArr[0]);
double *_12096_v7835 = ((double *) argArr[1]);
double *_12141_v7852 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12145_v7860[global_id + _12145_v7860Offset] = pow(pow((_12096_v7835[(global_id / 1600) + _12096_v7835Offset] / 20.0) + 1.0E-5, 0.5), -1.0) * (((((- (global_id % 80)) + (global_id / 80)) == 0) ? 1 : 0) + (_12141_v7852[(((global_id / 1600) * 80) + (global_id % 80)) + _12141_v7852Offset] * -0.05));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
