/*
 * One function: the release fence the fake-input ring writer needs.
 *
 * FakeInputWriter publishes events into a shared-memory ring that libfakeinput.so reads from
 * another process. Java's memory model says nothing about what a foreign reader sees, so the
 * writer calls this between filling the events and advancing the sequence counter: everything
 * written before it is visible to the reader before the counter moves.
 */
#include <jni.h>
#include <stdatomic.h>

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_input_FakeInputWriter_nativeStoreFence(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    atomic_thread_fence(memory_order_release);
}
