package visitors

import jdk.internal.org.objectweb.asm.*

class EmptyFieldVisitor : FieldVisitor(Opcodes.ASM5) {
    companion object {
        lateinit var instance: EmptyFieldVisitor
    }

    init {
        instance = this
    }
}