#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation584_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9116_v5883Offset = (int) offsetArr[0];
jint _9065_v5868Offset = (int) offsetArr[1];
jint _9115_v5882Offset = (int) offsetArr[2];
jint _9116_v5883Size = (int) sizeArr[0];
jint _9065_v5868Size = (int) sizeArr[1];
jint _9115_v5882Size = (int) sizeArr[2];
jint _9116_v5883Dim0 = (int) dim0Arr[0];
jint _9065_v5868Dim0 = (int) dim0Arr[1];
jint _9115_v5882Dim0 = (int) dim0Arr[2];
double *_9116_v5883 = ((double *) argArr[0]);
double *_9065_v5868 = ((double *) argArr[1]);
double *_9115_v5882 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9116_v5883[global_id + _9116_v5883Offset] = ((- ((_9065_v5868[((global_id / 16) * 2) + _9065_v5868Offset + 1] + _9065_v5868[((global_id / 16) * 2) + _9065_v5868Offset]) / 2.0)) + _9065_v5868[(global_id / 8) + _9065_v5868Offset]) * _9115_v5882[(((global_id / 16) * 8) + (global_id % 8)) + _9115_v5882Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
