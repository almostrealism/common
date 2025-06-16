#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation620_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9482_v6313Offset = (int) offsetArr[0];
jint _9463_v6298Offset = (int) offsetArr[1];
jint _9482_v6313Size = (int) sizeArr[0];
jint _9463_v6298Size = (int) sizeArr[1];
jint _9482_v6313Dim0 = (int) dim0Arr[0];
jint _9463_v6298Dim0 = (int) dim0Arr[1];
double *_9482_v6313 = ((double *) argArr[0]);
double *_9463_v6298 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9482_v6313[_9482_v6313Offset] = ((((- ((_9463_v6298[_9463_v6298Offset + 6] + _9463_v6298[_9463_v6298Offset + 7]) / 2.0)) + _9463_v6298[_9463_v6298Offset + 6]) * ((- ((_9463_v6298[_9463_v6298Offset + 6] + _9463_v6298[_9463_v6298Offset + 7]) / 2.0)) + _9463_v6298[_9463_v6298Offset + 6])) + (((- ((_9463_v6298[_9463_v6298Offset + 6] + _9463_v6298[_9463_v6298Offset + 7]) / 2.0)) + _9463_v6298[_9463_v6298Offset + 7]) * ((- ((_9463_v6298[_9463_v6298Offset + 6] + _9463_v6298[_9463_v6298Offset + 7]) / 2.0)) + _9463_v6298[_9463_v6298Offset + 7]))) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
