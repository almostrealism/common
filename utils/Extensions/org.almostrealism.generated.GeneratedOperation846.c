#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation846_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12727_v8367Offset = (int) offsetArr[0];
jint _12722_v8364Offset = (int) offsetArr[1];
jint _12727_v8367Size = (int) sizeArr[0];
jint _12722_v8364Size = (int) sizeArr[1];
jint _12727_v8367Dim0 = (int) dim0Arr[0];
jint _12722_v8364Dim0 = (int) dim0Arr[1];
double *_12727_v8367 = ((double *) argArr[0]);
double *_12722_v8364 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12727_v8367[global_id + _12727_v8367Offset] = (_12722_v8364[global_id + _12722_v8364Offset + 60] + -0.06059829625443345) / 0.030951540044903508;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
