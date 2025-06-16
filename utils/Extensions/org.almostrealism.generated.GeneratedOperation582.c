#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation582_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9076_v5968Offset = (int) offsetArr[0];
jint _9071_v5957Offset = (int) offsetArr[1];
jint _9076_v5968Size = (int) sizeArr[0];
jint _9071_v5957Size = (int) sizeArr[1];
jint _9076_v5968Dim0 = (int) dim0Arr[0];
jint _9071_v5957Dim0 = (int) dim0Arr[1];
double *_9076_v5968 = ((double *) argArr[0]);
double *_9071_v5957 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9076_v5968[global_id + _9076_v5968Offset] = ((- ((_9071_v5957[((global_id / 2) * 2) + _9071_v5957Offset + 1] + _9071_v5957[((global_id / 2) * 2) + _9071_v5957Offset]) / 2.0)) + _9071_v5957[global_id + _9071_v5957Offset]) * ((- ((_9071_v5957[((global_id / 2) * 2) + _9071_v5957Offset + 1] + _9071_v5957[((global_id / 2) * 2) + _9071_v5957Offset]) / 2.0)) + _9071_v5957[global_id + _9071_v5957Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
