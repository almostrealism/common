#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation658_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10215_v6723Offset = (int) offsetArr[0];
jint _10210_v6720Offset = (int) offsetArr[1];
jint _10215_v6723Size = (int) sizeArr[0];
jint _10210_v6720Size = (int) sizeArr[1];
jint _10215_v6723Dim0 = (int) dim0Arr[0];
jint _10210_v6720Dim0 = (int) dim0Arr[1];
double *_10215_v6723 = ((double *) argArr[0]);
double *_10210_v6720 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10215_v6723[global_id + _10215_v6723Offset] = (_10210_v6720[global_id + _10210_v6720Offset + 4] + -0.0613633251748405) / 0.024415933824948104;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
