#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation639_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10010_v6333Offset = (int) offsetArr[0];
jint _10011_v6334Offset = (int) offsetArr[1];
jint _10010_v6333Size = (int) sizeArr[0];
jint _10011_v6334Size = (int) sizeArr[1];
jint _10010_v6333Dim0 = (int) dim0Arr[0];
jint _10011_v6334Dim0 = (int) dim0Arr[1];
double *_10010_v6333 = ((double *) argArr[0]);
double *_10011_v6334 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10011_v6334[global_id + _10011_v6334Offset] = _10010_v6333[(global_id * 16) + _10010_v6333Offset + 8] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 1] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 13] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 2] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 3] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 4] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 15] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 12] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 5] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 6] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 11] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 7] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 14] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 10] + _10010_v6333[(global_id * 16) + _10010_v6333Offset + 9] + _10010_v6333[(global_id * 16) + _10010_v6333Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
