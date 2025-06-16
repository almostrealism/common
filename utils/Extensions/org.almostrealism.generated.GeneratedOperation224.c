#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation224_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2673_v2512Offset = (int) offsetArr[0];
jint _2674_v2514Offset = (int) offsetArr[1];
jint _2673_v2512Size = (int) sizeArr[0];
jint _2674_v2514Size = (int) sizeArr[1];
jint _2673_v2512Dim0 = (int) dim0Arr[0];
jint _2674_v2514Dim0 = (int) dim0Arr[1];
double *_2673_v2512 = ((double *) argArr[0]);
double *_2674_v2514 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2674_v2514[global_id + _2674_v2514Offset] = _2673_v2512[(global_id * 8) + _2673_v2512Offset + 1] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 2] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 3] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 4] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 5] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 6] + _2673_v2512[(global_id * 8) + _2673_v2512Offset + 7] + _2673_v2512[(global_id * 8) + _2673_v2512Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
