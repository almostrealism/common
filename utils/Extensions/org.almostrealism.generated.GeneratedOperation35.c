#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation35_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _759_v469Offset = (int) offsetArr[0];
jint _759_v470Offset = (int) offsetArr[1];
jint _759_v469Size = (int) sizeArr[0];
jint _759_v470Size = (int) sizeArr[1];
jint _759_v469Dim0 = (int) dim0Arr[0];
jint _759_v470Dim0 = (int) dim0Arr[1];
double *_759_v469 = ((double *) argArr[0]);
double *_759_v470 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_759_v469[global_id + _759_v469Offset] = (global_id == 30) ? 188.49555921538757 : ((global_id == 29) ? 182.212373908208 : ((global_id == 28) ? 175.92918860102841 : ((global_id == 27) ? 169.64600329384882 : _759_v470[global_id + _759_v470Offset])));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
