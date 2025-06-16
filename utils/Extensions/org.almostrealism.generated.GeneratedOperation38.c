#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation38_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _777_v511Offset = (int) offsetArr[0];
jint _777_v512Offset = (int) offsetArr[1];
jint _777_v514Offset = (int) offsetArr[2];
jint _777_v511Size = (int) sizeArr[0];
jint _777_v512Size = (int) sizeArr[1];
jint _777_v514Size = (int) sizeArr[2];
jint _777_v511Dim0 = (int) dim0Arr[0];
jint _777_v512Dim0 = (int) dim0Arr[1];
jint _777_v514Dim0 = (int) dim0Arr[2];
double *_777_v511 = ((double *) argArr[0]);
double *_777_v512 = ((double *) argArr[1]);
double *_777_v514 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double result = 0.0;
for (int i = 0; i <= 30;) {
jint index = (i - 15) + global_id;
if ((index >= 0) & (index < 10000)) {
result = (_777_v512[(index % 10000) + _777_v512Offset] * _777_v514[(i % 31) + _777_v514Offset]) + result;
}
i = i + 1;
}
_777_v511[global_id + _777_v511Offset] = result;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
