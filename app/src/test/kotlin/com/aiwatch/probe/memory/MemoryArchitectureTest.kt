package com.aiwatch.probe.memory

import java.io.File
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiled-code guard, not source grep. Includes compiler-generated suspend/lambda classes. */
class MemoryArchitectureTest {
    private val activity = "com/aiwatch/probe/memory/MemoryTrustActivity"
    private val repository = "com/aiwatch/probe/memory/MemoryTrustRepository"
    private val gateway = "com/aiwatch/memory/MemoryGateway"
    private data class Call(val caller: String, val method: String, val owner: String, val target: String)

    private fun calls(bytes: ByteArray): List<Call> {
        val result = mutableListOf<Call>()
        val reader = ClassReader(bytes)
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, target: String, descriptor: String, isInterface: Boolean) {
                        result += Call(reader.className, name, owner, target)
                    }
                }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return result
    }

    private fun violations(calls: List<Call>): List<Call> = calls.filter { call ->
        val inRepository = call.caller == repository || call.caller.startsWith(repository + "$")
        val directGateway = call.owner == gateway || call.owner.startsWith("com/aiwatch/memory/remote/") ||
            call.owner == "com/aiwatch/memory/DefaultMemoryGateway"
        val trustUi = call.caller == activity || call.caller.startsWith(activity + "$")
        val transport = call.owner.startsWith("okhttp3/") || call.owner.startsWith("java/net/") ||
            (call.owner.startsWith("com/aiwatch/protocol/") &&
                call.owner != "com/aiwatch/protocol/DeviceIdentityStore" &&
                call.owner != "com/aiwatch/protocol/DeviceIdentity")
        (!inRepository && directGateway) || (trustUi && transport) ||
            (call.owner == repository && call.target == "<init>" && call.caller != repository &&
                !(call.caller == activity && call.method == "repository"))
    }

    @Test fun productionHasOneConstructionSiteAndNoGatewayBypass() {
        val root = File(requireNotNull(System.getProperty("w4.classesDir")))
        val files = root.resolve("com/aiwatch/probe").walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(files.isNotEmpty(), "Missing production class files must fail, not silently pass")
        val instructions = files.flatMap { calls(it.readBytes()) }
        assertEquals(emptyList(), violations(instructions), "UI must use the repository, not gateway/transport")
        // Kotlin's default-argument constructor delegates internally; it is not another owner.
        val constructors = instructions.filter { it.owner == repository && it.target == "<init>" && it.caller != repository }
        assertEquals(1, constructors.size, "Exactly one production repository constructor call")
        assertTrue(instructions.any { it.caller.startsWith(activity + "$") }, "Inspect coroutine/lambda classes too")
    }

    private fun fixture(owner: String, target: String): List<Call> {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, activity + "$" + "BadPath", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "unusedPath", "()V", null, null)
        method.visitCode()
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, target, "()V", false)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 1)
        method.visitEnd()
        writer.visitEnd()
        return calls(writer.toByteArray())
    }

    @Test fun guardRejectsDirectGatewayEvenInAnUnusedLambda() {
        assertEquals(1, violations(fixture(gateway, "confirm")).size)
    }
    @Test fun guardRejectsASecondRepositoryConstructionSite() {
        assertEquals(1, violations(fixture(repository, "<init>")).size)
    }
    @Test fun guardRejectsDirectTransportInTrustUi() {
        assertEquals(1, violations(fixture("okhttp3/OkHttpClient", "newCall")).size)
    }
}
