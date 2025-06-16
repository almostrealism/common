#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation96_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1171_v837Offset = (int) offsetArr[0];
jint _1171_v838Offset = (int) offsetArr[1];
jint _1171_v840Offset = (int) offsetArr[2];
jint _1171_v837Size = (int) sizeArr[0];
jint _1171_v838Size = (int) sizeArr[1];
jint _1171_v840Size = (int) sizeArr[2];
jint _1171_v837Dim0 = (int) dim0Arr[0];
jint _1171_v838Dim0 = (int) dim0Arr[1];
jint _1171_v840Dim0 = (int) dim0Arr[2];
double *_1171_v837 = ((double *) argArr[0]);
double *_1171_v838 = ((double *) argArr[1]);
double *_1171_v840 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1171_v837[global_id + _1171_v837Offset] = (_1171_v838[(global_id / 3) + _1171_v838Offset + 2] * _1171_v840[(global_id % 3) + _1171_v840Offset + 3]) + (_1171_v838[(global_id / 3) + _1171_v838Offset + 4] * _1171_v840[(global_id % 3) + _1171_v840Offset + 6]) + (_1171_v838[(global_id / 3) + _1171_v838Offset] * _1171_v840[(global_id % 3) + _1171_v840Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
