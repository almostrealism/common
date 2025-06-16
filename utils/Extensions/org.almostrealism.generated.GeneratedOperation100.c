#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation100_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1180_v866Offset = (int) offsetArr[0];
jint _1180_v867Offset = (int) offsetArr[1];
jint _1180_v869Offset = (int) offsetArr[2];
jint _1180_v866Size = (int) sizeArr[0];
jint _1180_v867Size = (int) sizeArr[1];
jint _1180_v869Size = (int) sizeArr[2];
jint _1180_v866Dim0 = (int) dim0Arr[0];
jint _1180_v867Dim0 = (int) dim0Arr[1];
jint _1180_v869Dim0 = (int) dim0Arr[2];
double *_1180_v866 = ((double *) argArr[0]);
double *_1180_v867 = ((double *) argArr[1]);
double *_1180_v869 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1180_v866[global_id + _1180_v866Offset] = (_1180_v867[((((global_id % 10) / 5) * 1) + ((global_id / 10) * 6)) + _1180_v867Offset] * _1180_v869[(((global_id / 10) * 15) + (global_id % 5)) + _1180_v869Offset]) + (_1180_v869[(((global_id / 10) * 15) + (global_id % 5) + 5) + _1180_v869Offset] * _1180_v867[(((global_id % 10) / 5) + ((global_id / 10) * 6) + 2) + _1180_v867Offset]) + (_1180_v869[(((global_id / 10) * 15) + (global_id % 5) + 10) + _1180_v869Offset] * _1180_v867[(((global_id % 10) / 5) + ((global_id / 10) * 6) + 4) + _1180_v867Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
