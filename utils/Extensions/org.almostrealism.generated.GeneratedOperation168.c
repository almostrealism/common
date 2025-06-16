#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation168_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2202_v2103Offset = (int) offsetArr[0];
jint _2202_v2104Offset = (int) offsetArr[1];
jint _2202_v2103Size = (int) sizeArr[0];
jint _2202_v2104Size = (int) sizeArr[1];
jint _2202_v2103Dim0 = (int) dim0Arr[0];
jint _2202_v2104Dim0 = (int) dim0Arr[1];
double *_2202_v2103 = ((double *) argArr[0]);
double *_2202_v2104 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2202_v2103[global_id + _2202_v2103Offset] = _2202_v2104[(((global_id % 18) * 9) + (global_id / 18)) + _2202_v2104Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
