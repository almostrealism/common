#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation29_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _632_v406Offset = (int) offsetArr[0];
jint _632_v406Size = (int) sizeArr[0];
jint _632_v406Dim0 = (int) dim0Arr[0];
double *_632_v406 = ((double *) argArr[0]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_632_v406[global_id + _632_v406Offset] = (((global_id == 10) ? 1 : 0) ? 0.36281179138321995 : ((sin(((global_id == 20) ? 31.41592653589793 : ((global_id == 19) ? 28.274333882308138 : ((global_id == 18) ? 25.132741228718345 : ((global_id == 17) ? 21.991148575128552 : ((global_id == 16) ? 18.84955592153876 : ((global_id == 15) ? 15.707963267948966 : ((global_id == 14) ? 12.566370614359172 : ((global_id == 13) ? 9.42477796076938 : ((global_id == 12) ? 6.283185307179586 : ((global_id == 11) ? 3.141592653589793 : ((!(global_id == 10)) ? ((global_id == 9) ? -3.141592653589793 : ((global_id == 8) ? -6.283185307179586 : ((global_id == 7) ? -9.42477796076938 : ((global_id == 6) ? -12.566370614359172 : ((global_id == 5) ? -15.707963267948966 : ((global_id == 4) ? -18.84955592153876 : ((global_id == 3) ? -21.991148575128552 : ((global_id == 2) ? -25.132741228718345 : ((global_id == 1) ? -28.274333882308138 : -31.41592653589793))))))))) : 0))))))))))) * 0.36281179138321995)) / ((global_id == 20) ? 31.41592653589793 : ((global_id == 19) ? 28.274333882308138 : ((global_id == 18) ? 25.132741228718345 : ((global_id == 17) ? 21.991148575128552 : ((global_id == 16) ? 18.84955592153876 : ((global_id == 15) ? 15.707963267948966 : ((global_id == 14) ? 12.566370614359172 : ((global_id == 13) ? 9.42477796076938 : ((global_id == 12) ? 6.283185307179586 : ((global_id == 11) ? 3.141592653589793 : ((!(global_id == 10)) ? ((global_id == 9) ? -3.141592653589793 : ((global_id == 8) ? -6.283185307179586 : ((global_id == 7) ? -9.42477796076938 : ((global_id == 6) ? -12.566370614359172 : ((global_id == 5) ? -15.707963267948966 : ((global_id == 4) ? -18.84955592153876 : ((global_id == 3) ? -21.991148575128552 : ((global_id == 2) ? -25.132741228718345 : ((global_id == 1) ? -28.274333882308138 : -31.41592653589793))))))))) : 0))))))))))))) * ((- ((global_id == 20) ? 0.46 : ((global_id == 19) ? 0.43748599749577066 : ((global_id == 18) ? 0.37214781741247577 : ((global_id == 17) ? 0.2703812160545376 : ((global_id == 16) ? 0.14214781741247573 : ((global_id == 15) ? -8.450062914116737E-17 : ((global_id == 14) ? -0.1421478174124759 : ((global_id == 13) ? -0.2703812160545377 : ((global_id == 12) ? -0.3721478174124759 : ((global_id == 11) ? -0.4374859974957708 : ((global_id == 10) ? -0.46 : ((global_id == 9) ? -0.43748599749577066 : ((global_id == 8) ? -0.37214781741247577 : ((global_id == 7) ? -0.2703812160545376 : ((global_id == 6) ? -0.14214781741247579 : ((global_id == 5) ? 2.816687638038912E-17 : ((global_id == 4) ? 0.14214781741247584 : ((global_id == 3) ? 0.27038121605453763 : ((global_id == 2) ? 0.3721478174124758 : ((global_id == 1) ? 0.43748599749577066 : 0.46))))))))))))))))))))) + 0.54);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
