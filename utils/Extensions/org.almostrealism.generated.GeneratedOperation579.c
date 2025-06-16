#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation579_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9106_v6023Offset = (int) offsetArr[0];
jint _9071_v5976Offset = (int) offsetArr[1];
jint _9091_v5992Offset = (int) offsetArr[2];
jint _9100_v6010Offset = (int) offsetArr[3];
jint _9106_v6023Size = (int) sizeArr[0];
jint _9071_v5976Size = (int) sizeArr[1];
jint _9091_v5992Size = (int) sizeArr[2];
jint _9100_v6010Size = (int) sizeArr[3];
jint _9106_v6023Dim0 = (int) dim0Arr[0];
jint _9071_v5976Dim0 = (int) dim0Arr[1];
jint _9091_v5992Dim0 = (int) dim0Arr[2];
jint _9100_v6010Dim0 = (int) dim0Arr[3];
double *_9106_v6023 = ((double *) argArr[0]);
double *_9071_v5976 = ((double *) argArr[1]);
double *_9091_v5992 = ((double *) argArr[2]);
double *_9100_v6010 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9106_v6023[global_id + _9106_v6023Offset] = ((((_9091_v5992[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9091_v5992Offset + 1] + _9091_v5992[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9091_v5992Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0)) * ((- ((_9071_v5976[((global_id / 16) * 2) + _9071_v5976Offset + 1] + _9071_v5976[((global_id / 16) * 2) + _9071_v5976Offset]) / 2.0)) + _9071_v5976[(global_id / 8) + _9071_v5976Offset])) + ((((_9100_v6010[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9100_v6010Offset + 1] + _9100_v6010[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _9100_v6010Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0)) * ((- ((_9071_v5976[((global_id / 16) * 2) + _9071_v5976Offset + 1] + _9071_v5976[((global_id / 16) * 2) + _9071_v5976Offset]) / 2.0)) + _9071_v5976[(global_id / 8) + _9071_v5976Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
