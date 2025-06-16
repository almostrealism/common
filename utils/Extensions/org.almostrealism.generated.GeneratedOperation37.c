#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation37_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _776_v506Offset = (int) offsetArr[0];
jint _776_v509Offset = (int) offsetArr[1];
jint _776_v506Size = (int) sizeArr[0];
jint _776_v509Size = (int) sizeArr[1];
jint _776_v506Dim0 = (int) dim0Arr[0];
jint _776_v509Dim0 = (int) dim0Arr[1];
double *_776_v506 = ((double *) argArr[0]);
double *_776_v509 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_776_v506[global_id + _776_v506Offset] = (((((global_id == 30) ? 30.0 : ((global_id == 29) ? 29.0 : ((global_id == 28) ? 28.0 : ((global_id == 27) ? 27.0 : _776_v509[global_id + _776_v509Offset])))) + -15.0) == 0) ? 0.1360544217687075 : ((sin(((global_id == 30) ? 47.12388980384689 : ((global_id == 29) ? 43.982297150257104 : ((global_id == 28) ? 40.840704496667314 : ((global_id == 27) ? 37.69911184307752 : ((global_id == 26) ? 34.55751918948772 : ((global_id == 25) ? 31.41592653589793 : ((global_id == 24) ? 28.274333882308138 : ((global_id == 23) ? 25.132741228718345 : ((global_id == 22) ? 21.991148575128552 : ((global_id == 21) ? 18.84955592153876 : ((global_id == 20) ? 15.707963267948966 : ((global_id == 19) ? 12.566370614359172 : ((global_id == 18) ? 9.42477796076938 : ((global_id == 17) ? 6.283185307179586 : ((global_id == 16) ? 3.141592653589793 : ((!(global_id == 15)) ? ((global_id == 14) ? -3.141592653589793 : ((global_id == 13) ? -6.283185307179586 : ((global_id == 12) ? -9.42477796076938 : ((global_id == 11) ? -12.566370614359172 : ((global_id == 10) ? -15.707963267948966 : ((global_id == 9) ? -18.84955592153876 : ((global_id == 8) ? -21.991148575128552 : ((global_id == 7) ? -25.132741228718345 : ((global_id == 6) ? -28.274333882308138 : ((global_id == 5) ? -31.41592653589793 : ((global_id == 4) ? -34.55751918948772 : ((global_id == 3) ? -37.69911184307752 : ((global_id == 2) ? -40.840704496667314 : ((global_id == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))) * 0.1360544217687075)) / ((global_id == 30) ? 47.12388980384689 : ((global_id == 29) ? 43.982297150257104 : ((global_id == 28) ? 40.840704496667314 : ((global_id == 27) ? 37.69911184307752 : ((global_id == 26) ? 34.55751918948772 : ((global_id == 25) ? 31.41592653589793 : ((global_id == 24) ? 28.274333882308138 : ((global_id == 23) ? 25.132741228718345 : ((global_id == 22) ? 21.991148575128552 : ((global_id == 21) ? 18.84955592153876 : ((global_id == 20) ? 15.707963267948966 : ((global_id == 19) ? 12.566370614359172 : ((global_id == 18) ? 9.42477796076938 : ((global_id == 17) ? 6.283185307179586 : ((global_id == 16) ? 3.141592653589793 : ((!(global_id == 15)) ? ((global_id == 14) ? -3.141592653589793 : ((global_id == 13) ? -6.283185307179586 : ((global_id == 12) ? -9.42477796076938 : ((global_id == 11) ? -12.566370614359172 : ((global_id == 10) ? -15.707963267948966 : ((global_id == 9) ? -18.84955592153876 : ((global_id == 8) ? -21.991148575128552 : ((global_id == 7) ? -25.132741228718345 : ((global_id == 6) ? -28.274333882308138 : ((global_id == 5) ? -31.41592653589793 : ((global_id == 4) ? -34.55751918948772 : ((global_id == 3) ? -37.69911184307752 : ((global_id == 2) ? -40.840704496667314 : ((global_id == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))))) * ((- ((global_id == 30) ? 0.46 : ((global_id == 29) ? 0.4499478963375506 : ((global_id == 28) ? 0.42023091051559647 : ((global_id == 27) ? 0.37214781741247577 : (_776_v509[global_id + _776_v509Offset + 31] + 0.46)))))) + 0.54);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
