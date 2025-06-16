#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation556_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7868_v5643Offset = (int) offsetArr[0];
jint _7861_v5627Offset = (int) offsetArr[1];
jint _7868_v5643Size = (int) sizeArr[0];
jint _7861_v5627Size = (int) sizeArr[1];
jint _7868_v5643Dim0 = (int) dim0Arr[0];
jint _7861_v5627Dim0 = (int) dim0Arr[1];
double *_7868_v5643 = ((double *) argArr[0]);
double *_7861_v5627 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7868_v5643[_7868_v5643Offset] = ((((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset]) * ((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset])) + (((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset + 1]) * ((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset + 1])) + (((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset + 2]) * ((- ((_7861_v5627[_7861_v5627Offset] + _7861_v5627[_7861_v5627Offset + 1] + _7861_v5627[_7861_v5627Offset + 2]) / 3.0)) + _7861_v5627[_7861_v5627Offset + 2]))) / 3.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
