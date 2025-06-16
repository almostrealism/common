#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation834_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12593_v8162Offset = (int) offsetArr[0];
jint _12544_v8137Offset = (int) offsetArr[1];
jint _12589_v8154Offset = (int) offsetArr[2];
jint _12593_v8162Size = (int) sizeArr[0];
jint _12544_v8137Size = (int) sizeArr[1];
jint _12589_v8154Size = (int) sizeArr[2];
jint _12593_v8162Dim0 = (int) dim0Arr[0];
jint _12544_v8137Dim0 = (int) dim0Arr[1];
jint _12589_v8154Dim0 = (int) dim0Arr[2];
double *_12593_v8162 = ((double *) argArr[0]);
double *_12544_v8137 = ((double *) argArr[1]);
double *_12589_v8154 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12593_v8162[global_id + _12593_v8162Offset] = pow(pow((_12544_v8137[(global_id / 3600) + _12544_v8137Offset] / 30.0) + 1.0E-5, 0.5), -1.0) * (((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_12589_v8154[(((global_id / 3600) * 120) + (global_id % 120)) + _12589_v8154Offset] * -0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
