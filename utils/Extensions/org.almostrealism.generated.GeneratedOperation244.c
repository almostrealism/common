#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation244_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2751_v2629Offset = (int) offsetArr[0];
jint _2751_v2631Offset = (int) offsetArr[1];
jint _2752_v2633Offset = (int) offsetArr[2];
jint _2751_v2629Size = (int) sizeArr[0];
jint _2751_v2631Size = (int) sizeArr[1];
jint _2752_v2633Size = (int) sizeArr[2];
jint _2751_v2629Dim0 = (int) dim0Arr[0];
jint _2751_v2631Dim0 = (int) dim0Arr[1];
jint _2752_v2633Dim0 = (int) dim0Arr[2];
double *_2751_v2629 = ((double *) argArr[0]);
double *_2751_v2631 = ((double *) argArr[1]);
double *_2752_v2633 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2752_v2633[global_id + _2752_v2633Offset] = pow(_2751_v2629[global_id + _2751_v2629Offset], _2751_v2631[global_id + _2751_v2631Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
