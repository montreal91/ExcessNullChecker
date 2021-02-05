package visitors

import Logger
import NullType
import jdk.internal.org.objectweb.asm.*

enum class BypassType {
    Constructors,
    Methods,
    All
}

class MethodAnalyzer(
    private val bypassType: BypassType,
    private val finalFields: MutableMap<String, NullType>,
    private val processedMethods: MutableMap<String, NullType>,
    private val availableMethods: Set<String>,
    private val methodToProcess: String? = null, private val classFileData: ByteArray,
    private val logger: Logger
)
    : ClassVisitor(Opcodes.ASM5) {

    override fun visitMethod(p0: Int, p1: String?, p2: String?, p3: String?, p4: Array<out String>?): MethodVisitor {
        val isConstructor = p1.equals("<init>")

        if (bypassType == BypassType.Constructors && !isConstructor ||
            bypassType == BypassType.Methods && isConstructor) {
            return EmptyMethodVisitor.instance
        }

        val isStatic = (p0 and Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC
        val signature = Signature.get(isStatic, p1, p2)

        if (methodToProcess != null && methodToProcess != signature.fullName) {
            return EmptyMethodVisitor.instance
        }

        if (!processedMethods.contains(signature.fullName)) {
            val isFinalOrStatic =
                p0 and (Opcodes.ACC_FINAL or Opcodes.ACC_STATIC) != 0 // Other methods may be overridden
            processedMethods[signature.fullName] = if (isFinalOrStatic) NullType.Uninitialized else NullType.Mixed
            return CodeAnalyzer(
                signature,
                finalFields,
                processedMethods,
                availableMethods,
                classFileData,
                logger
            )
        }

        return EmptyMethodVisitor.instance
    }
}