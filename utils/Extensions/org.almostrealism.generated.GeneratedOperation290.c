#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation290_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3521_v3091Offset = (int) offsetArr[0];
jint _3502_v3076Offset = (int) offsetArr[1];
jint _3521_v3091Size = (int) sizeArr[0];
jint _3502_v3076Size = (int) sizeArr[1];
jint _3521_v3091Dim0 = (int) dim0Arr[0];
jint _3502_v3076Dim0 = (int) dim0Arr[1];
double *_3521_v3091 = ((double *) argArr[0]);
double *_3502_v3076 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3521_v3091[_3521_v3091Offset] = ((((- ((_3502_v3076[_3502_v3076Offset] + _3502_v3076[_3502_v3076Offset + 1]) / 2.0)) + _3502_v3076[_3502_v3076Offset]) * ((- ((_3502_v3076[_3502_v3076Offset] + _3502_v3076[_3502_v3076Offset + 1]) / 2.0)) + _3502_v3076[_3502_v3076Offset])) + (((- ((_3502_v3076[_3502_v3076Offset] + _3502_v3076[_3502_v3076Offset + 1]) / 2.0)) + _3502_v3076[_3502_v3076Offset + 1]) * ((- ((_3502_v3076[_3502_v3076Offset] + _3502_v3076[_3502_v3076Offset + 1]) / 2.0)) + _3502_v3076[_3502_v3076Offset + 1]))) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
