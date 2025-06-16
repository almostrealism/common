#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation518_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7126_v5162Offset = (int) offsetArr[0];
jint _7121_v5151Offset = (int) offsetArr[1];
jint _7126_v5162Size = (int) sizeArr[0];
jint _7121_v5151Size = (int) sizeArr[1];
jint _7126_v5162Dim0 = (int) dim0Arr[0];
jint _7121_v5151Dim0 = (int) dim0Arr[1];
double *_7126_v5162 = ((double *) argArr[0]);
double *_7121_v5151 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7126_v5162[global_id + _7126_v5162Offset] = ((- ((_7121_v5151[_7121_v5151Offset] + _7121_v5151[_7121_v5151Offset + 1]) / 2.0)) + _7121_v5151[global_id + _7121_v5151Offset]) * ((- ((_7121_v5151[_7121_v5151Offset] + _7121_v5151[_7121_v5151Offset + 1]) / 2.0)) + _7121_v5151[global_id + _7121_v5151Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
