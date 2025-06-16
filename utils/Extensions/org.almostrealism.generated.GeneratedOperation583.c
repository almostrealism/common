#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation583_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9114_v5953Offset = (int) offsetArr[0];
jint _9077_v5921Offset = (int) offsetArr[1];
jint _9111_v5947Offset = (int) offsetArr[2];
jint _9114_v5953Size = (int) sizeArr[0];
jint _9077_v5921Size = (int) sizeArr[1];
jint _9111_v5947Size = (int) sizeArr[2];
jint _9114_v5953Dim0 = (int) dim0Arr[0];
jint _9077_v5921Dim0 = (int) dim0Arr[1];
jint _9111_v5947Dim0 = (int) dim0Arr[2];
double *_9114_v5953 = ((double *) argArr[0]);
double *_9077_v5921 = ((double *) argArr[1]);
double *_9111_v5947 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9114_v5953[global_id + _9114_v5953Offset] = (- pow(pow(((_9077_v5921[((global_id / 8) * 2) + _9077_v5921Offset + 1] + _9077_v5921[((global_id / 8) * 2) + _9077_v5921Offset]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_9077_v5921[((global_id / 8) * 2) + _9077_v5921Offset + 1] + _9077_v5921[((global_id / 8) * 2) + _9077_v5921Offset]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_9111_v5947[(global_id * 2) + _9111_v5947Offset + 1] + _9111_v5947[(global_id * 2) + _9111_v5947Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
