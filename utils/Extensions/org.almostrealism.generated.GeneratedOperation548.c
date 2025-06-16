#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation548_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7759_v5559Offset = (int) offsetArr[0];
jint _7725_v5509Offset = (int) offsetArr[1];
jint _7744_v5525Offset = (int) offsetArr[2];
jint _7752_v5543Offset = (int) offsetArr[3];
jint _7759_v5559Size = (int) sizeArr[0];
jint _7725_v5509Size = (int) sizeArr[1];
jint _7744_v5525Size = (int) sizeArr[2];
jint _7752_v5543Size = (int) sizeArr[3];
jint _7759_v5559Dim0 = (int) dim0Arr[0];
jint _7725_v5509Dim0 = (int) dim0Arr[1];
jint _7744_v5525Dim0 = (int) dim0Arr[2];
jint _7752_v5543Dim0 = (int) dim0Arr[3];
double *_7759_v5559 = ((double *) argArr[0]);
double *_7725_v5509 = ((double *) argArr[1]);
double *_7744_v5525 = ((double *) argArr[2]);
double *_7752_v5543 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7759_v5559[global_id + _7759_v5559Offset] = ((((((- (((global_id % 3) * 3) + (global_id / 3))) + ((global_id % 3) * 4)) == 0) ? 1 : 0) + ((_7744_v5525[((global_id / 3) * 3) + _7744_v5525Offset + 1] + _7744_v5525[((global_id / 3) * 3) + _7744_v5525Offset + 2] + _7744_v5525[((global_id / 3) * 3) + _7744_v5525Offset]) * -0.3333333333333333)) * ((- ((_7725_v5509[_7725_v5509Offset] + _7725_v5509[_7725_v5509Offset + 1] + _7725_v5509[_7725_v5509Offset + 2]) / 3.0)) + _7725_v5509[(global_id % 3) + _7725_v5509Offset])) + ((((((- (((global_id % 3) * 3) + (global_id / 3))) + ((global_id % 3) * 4)) == 0) ? 1 : 0) + ((_7752_v5543[((global_id / 3) * 3) + _7752_v5543Offset + 1] + _7752_v5543[((global_id / 3) * 3) + _7752_v5543Offset + 2] + _7752_v5543[((global_id / 3) * 3) + _7752_v5543Offset]) * -0.3333333333333333)) * ((- ((_7725_v5509[_7725_v5509Offset] + _7725_v5509[_7725_v5509Offset + 1] + _7725_v5509[_7725_v5509Offset + 2]) / 3.0)) + _7725_v5509[(global_id % 3) + _7725_v5509Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
