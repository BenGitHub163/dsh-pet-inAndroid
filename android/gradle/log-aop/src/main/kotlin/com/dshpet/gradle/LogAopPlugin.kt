package com.dshpet.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter

/**
 * 日志 AOP 插件：把带 `@TraceLog` 的方法在**编译期**改写成
 *
 * ```
 * enter(module, method)  →  原方法体  →  exit(module, method, elapsedMs)
 * ```
 *
 * 为什么用 ASM 插桩而不是 AspectJ：
 *  - AspectJ 的 Gradle 插件在 AGP 8.x 上兼容性一直不稳，且会拖慢构建；
 *  - AGP 自带 Instrumentation API（`transformClassesWith`），是官方支持的字节码入口；
 *  - 没有运行时依赖，R8/Debug 一致，插桩只发生在显式标注的方法上。
 *
 * 只处理 `com.dshpet.android` 包，跳过 AppTrace 自身（否则自己插自己会递归）。
 */
class LogAopPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant ->
            variant.instrumentation.apply {
                transformClassesWith(
                    LogAopVisitorFactory::class.java,
                    InstrumentationScope.PROJECT,
                ) {}
                // 插桩新增了局部变量，必须重算栈帧
                setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
            }
        }
    }
}

/** 只对项目自身代码做插桩 */
abstract class LogAopVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return name.startsWith("com.dshpet.android") &&
            !name.startsWith("com.dshpet.android.util.AppTrace") &&
            !name.startsWith("com.dshpet.android.util.TraceLog") &&
            !name.contains("\$")
    }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                // 构造器 / 静态初始化 / 抽象方法不插桩（构造器里插代码要走 super 之后，容易错）
                if (mv == null ||
                    (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE)) != 0 ||
                    name == "<init>" || name == "<clinit>"
                ) {
                    return mv
                }
                return TraceMethodVisitor(mv, access, name, descriptor)
            }
        }
}

/** 真正干活的方法访问器：识别注解 + 插 enter/exit */
private class TraceMethodVisitor(
    mv: MethodVisitor,
    access: Int,
    private val methodName: String,
    descriptor: String,
) : AdviceAdapter(Opcodes.ASM9, mv, access, methodName, descriptor) {

    private var traced = false
    private var module = ""
    private var level = "D"
    private var elapsedLocal = -1

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor == "Lcom/dshpet/android/util/TraceLog;") {
            traced = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                    when (name) {
                        "module" -> module = value as? String ?: ""
                        "level" -> level = value as? String ?: "D"
                    }
                }
            }
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun onMethodEnter() {
        if (!traced) return
        elapsedLocal = newLocal(Type.LONG_TYPE)
        // start = System.nanoTime()
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LSTORE, elapsedLocal)
        // AppTrace.enter(level, module, method, "")
        push(level)
        push(module)
        push(methodName)
        push("")
        mv.visitMethodInsn(
            INVOKESTATIC,
            "com/dshpet/android/util/AppTrace",
            "enter",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            false,
        )
    }

    override fun onMethodExit(opcode: Int) {
        // 异常路径不记耗时（值会失真），交给上层 catch 打
        if (!traced || opcode == ATHROW || elapsedLocal < 0) return
        // elapsed = (System.nanoTime() - start) / 1_000_000
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false)
        mv.visitVarInsn(LLOAD, elapsedLocal)
        mv.visitInsn(LSUB)
        mv.visitLdcInsn(1_000_000L)
        mv.visitInsn(LDIV)
        val tmp = newLocal(Type.LONG_TYPE)
        mv.visitVarInsn(LSTORE, tmp)
        // AppTrace.exit(level, module, method, elapsed)
        push(level)
        push(module)
        push(methodName)
        mv.visitVarInsn(LLOAD, tmp)
        mv.visitMethodInsn(
            INVOKESTATIC,
            "com/dshpet/android/util/AppTrace",
            "exit",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
            false,
        )
    }
}
