package visitors

import AnotherCondition
import CfgLink
import CfgLinkType
import CfgNode
import DataEntry
import Dirty
import ExcessCheckMessage
import Logger
import NullCheckCondition
import NullType
import Signature
import State
import jdk.internal.org.objectweb.asm.*
import java.lang.UnsupportedOperationException
import Uninitialized

class CodeAnalyzer(
    private val signature: Signature,
    private val fields: Map<String, FieldInfo>,
    private val processedMethods: MutableMap<String, DataEntry>,
    private val processedFinalFields: MutableMap<String, NullType>,
    private val methodsCfg: Map<String, Map<Int, CfgNode>>,
    private val classFileData: ByteArray,
    private val logger: Logger
) : AdvancedVisitor() {
    private var currentState: State
    private val cfgNodes: Map<Int, CfgNode> = methodsCfg[signature.fullName]!!

    private val cfgNodeStates: MutableMap<CfgNode, State> = mutableMapOf()

    init {
        currentState = State(cfgNodes[0])
        var offset = 0
        if (!signature.static) {
            // First data entry is always not null for instance methods
            offset = 1
            currentState.push(DataEntry(0, NullType.NotNull))
        }
        for (i in 0 until signature.paramsCount) {
            // Initialize local variables from parameters
            currentState.push(DataEntry(i + offset, NullType.Mixed))
        }
        for (field in fields) {
            val processedFinalField = processedFinalFields[field.key]
            currentState.setField(field.key, DataEntry(field.key, processedFinalField ?: NullType.Mixed))
        }
    }

    override fun visitVarInsn(p0: Int, p1: Int) {
        checkState()
        when (p0)  {
            Opcodes.ILOAD,
            Opcodes.LLOAD,
            Opcodes.FLOAD,
            Opcodes.DLOAD -> {
                currentState.push(DataEntry(p1, NullType.Mixed))
            }
            Opcodes.ALOAD -> currentState.push(currentState.get(p1))

            Opcodes.ISTORE,
            Opcodes.LSTORE,
            Opcodes.FSTORE,
            Opcodes.DSTORE -> {
                currentState.set(p1, DataEntry(NullType.Mixed))
            }
            Opcodes.ASTORE -> {
                currentState.set(p1, currentState.pop())
            }

            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitInsn(p0: Int) {
        checkState()
        when (p0) {
            Opcodes.ACONST_NULL -> {
                currentState.push(DataEntry(NullType.Null))
            }
            Opcodes.ICONST_M1,
            Opcodes.ICONST_0,
            Opcodes.ICONST_1,
            Opcodes.ICONST_2,
            Opcodes.ICONST_3,
            Opcodes.ICONST_4,
            Opcodes.ICONST_5,
            Opcodes.LCONST_0,
            Opcodes.LCONST_1,
            Opcodes.FCONST_0,
            Opcodes.FCONST_1,
            Opcodes.FCONST_2,
            Opcodes.DCONST_0,
            Opcodes.DCONST_1 -> {
                currentState.push(DataEntry(NullType.Mixed))
            }
            Opcodes.DUP -> {
                currentState.push(currentState.peek())
            }
            Opcodes.IRETURN,
            Opcodes.DRETURN,
            Opcodes.FRETURN,
            Opcodes.LRETURN,
            Opcodes.ARETURN,
            Opcodes.RETURN -> {
                val currentDataEntry =
                    if (p0 != Opcodes.RETURN) currentState.pop() else DataEntry(Dirty, NullType.Mixed)

                val methodReturnEntry = processedMethods[signature.fullName]
                if (methodReturnEntry != null)
                    processedMethods[signature.fullName] = methodReturnEntry.merge(currentDataEntry)

                currentState.clear()
            }
            Opcodes.IADD,
            Opcodes.LADD,
            Opcodes.FADD,
            Opcodes.DADD,
            Opcodes.IMUL,
            Opcodes.LMUL,
            Opcodes.FMUL,
            Opcodes.DMUL,
            Opcodes.IDIV,
            Opcodes.LDIV,
            Opcodes.FDIV,
            Opcodes.DDIV,
            Opcodes.IREM,
            Opcodes.LREM,
            Opcodes.FREM,
            Opcodes.DREM,
            Opcodes.ISUB,
            Opcodes.LSUB,
            Opcodes.FSUB,
            Opcodes.DSUB,
            Opcodes.ISHL,
            Opcodes.LSHL,
            Opcodes.ISHR,
            Opcodes.LSHR,
            Opcodes.IUSHR,
            Opcodes.IAND,
            Opcodes.LAND,
            Opcodes.IOR,
            Opcodes.LOR,
            Opcodes.IXOR,
            Opcodes.LXOR,
            Opcodes.LCMP,
            Opcodes.FCMPL,
            Opcodes.FCMPG,
            Opcodes.DCMPL,
            Opcodes.DCMPG -> {
                currentState.pop()
                currentState.pop()
                currentState.push(DataEntry(NullType.Mixed))
            }
            Opcodes.INEG,
            Opcodes.LNEG,
            Opcodes.FNEG,
            Opcodes.DNEG,
            Opcodes.I2L,
            Opcodes.I2F,
            Opcodes.I2D,
            Opcodes.L2I,
            Opcodes.L2F,
            Opcodes.L2D,
            Opcodes.F2I,
            Opcodes.F2L,
            Opcodes.F2D,
            Opcodes.D2I,
            Opcodes.D2L,
            Opcodes.D2F,
            Opcodes.I2B,
            Opcodes.I2C,
            Opcodes.I2S -> {
                currentState.pop()
                currentState.push(DataEntry(NullType.Mixed))
            }
            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitIntInsn(p0: Int, p1: Int) {
        checkState()
        when (p0) {
            Opcodes.BIPUSH,
            Opcodes.SIPUSH -> {
                currentState.push(DataEntry(NullType.Mixed))
            }
            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitLdcInsn(p0: Any?) {
        checkState()
        currentState.push(DataEntry(NullType.NotNull))
        incOffset()
    }

    override fun visitTypeInsn(p0: Int, p1: String?) {
        checkState()
        when (p0) {
            Opcodes.NEW,
            Opcodes.NEWARRAY,
            Opcodes.ANEWARRAY -> {
                if (p0 != Opcodes.NEW)
                    currentState.pop() // pop array length
                currentState.push(DataEntry(NullType.NotNull))
            }
            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitMethodInsn(p0: Int, p1: String?, p2: String?, p3: String?, p4: Boolean) {
        checkState()
        val isStatic = p0 == Opcodes.INVOKESTATIC
        val signature = Signature.get(isStatic, p2, p3)
        if (p0 == Opcodes.INVOKEVIRTUAL || p0 == Opcodes.INVOKESPECIAL || p0 == Opcodes.INVOKESTATIC) {
            // Make virtual call and remove parameters from stack except of the first one
            val params = mutableListOf<DataEntry>()
            for (i in 0 until signature.paramsCount) {
                params.add(currentState.pop())
            }

            if (p0 != Opcodes.INVOKESTATIC) {
                popAndSetNotNull()
            }

            var returnDataEntry: DataEntry? = null
            var returnType: NullType = NullType.Mixed
            if (methodsCfg.containsKey(signature.fullName)) {
                returnDataEntry = processedMethods[signature.fullName]
                if (returnDataEntry == null) {
                    // Recursive analysing...
                    val methodAnalyzer = MethodAnalyzer(
                        BypassType.All,
                        fields,
                        processedMethods,
                        processedFinalFields,
                        methodsCfg,
                        signature.fullName,
                        classFileData,
                        logger
                    )
                    val classReader = ClassReader(classFileData)
                    classReader.accept(methodAnalyzer, 0)
                    returnDataEntry = processedMethods[signature.fullName]
                }
                returnType = returnDataEntry?.type ?: NullType.Mixed
            }

            // Try to link passed param with return value
            if (returnDataEntry != null && returnType == NullType.Mixed) {
                val linkedLocalVar = returnDataEntry.name.toIntOrNull() // Only local variables are relevant
                if (linkedLocalVar != null) {
                    returnType = params.getOrNull(linkedLocalVar)?.type ?: NullType.Mixed
                }
            }

            if (!signature.isVoid) {
                currentState.push(DataEntry(Uninitialized, returnType))
            }
        } else {
            throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitFieldInsn(p0: Int, p1: String?, p2: String?, p3: String?) {
        checkState()
        when (p0) {
            Opcodes.GETSTATIC,
            Opcodes.GETFIELD -> {
                if (p0 == Opcodes.GETFIELD) {
                    popAndSetNotNull()
                }
                if (p2 != null) {
                    val dataEntry = currentState.getField(p2)
                    currentState.push(dataEntry ?: DataEntry(Dirty, NullType.Mixed))
                }
            }
            Opcodes.PUTSTATIC,
            Opcodes.PUTFIELD -> {
                val dataEntry = currentState.pop()
                if (p2 != null) {
                    currentState.setField(p2, dataEntry)
                    val finalField = processedFinalFields[p2]
                    if (finalField != null) {
                        processedFinalFields[p2] = finalField.merge(dataEntry.type)
                    }
                }
                if (p0 == Opcodes.PUTFIELD) {
                    popAndSetNotNull()
                }
            }
            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitJumpInsn(p0: Int, p1: Label?) {
        checkState()
        when (p0) {
            Opcodes.IF_ICMPEQ,
            Opcodes.IF_ICMPNE,
            Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT,
            Opcodes.IF_ICMPLE,
            Opcodes.IF_ACMPEQ,
            Opcodes.IF_ACMPNE -> {
                currentState.pop()
                currentState.pop()
                currentState.condition = AnotherCondition(currentLine)
            }
            Opcodes.IFEQ,
            Opcodes.IFNE,
            Opcodes.IFLT,
            Opcodes.IFGE,
            Opcodes.IFGT,
            Opcodes.IFLE -> {
                currentState.pop()
                currentState.condition = AnotherCondition(currentLine)
            }
            Opcodes.GOTO -> {
                currentState.condition = null
            }
            Opcodes.IFNULL,
            Opcodes.IFNONNULL -> {
                val dataEntry = currentState.pop()
                val checkType = if (p0 == Opcodes.IFNULL) NullType.Null else NullType.NotNull
                val dataEntryType = dataEntry.type

                var conditionIsAlwaysTrue: Boolean? = null
                if (dataEntryType.isDefined()) {
                    conditionIsAlwaysTrue =
                        if (checkType == NullType.Null) dataEntryType == NullType.NotNull else dataEntryType == NullType.Null
                }
                else {
                    // TODO: support of complex nested return conditions
                }

                val condition = if (conditionIsAlwaysTrue != null) {
                    logger.log(ExcessCheckMessage(conditionIsAlwaysTrue, currentLine))
                    if (conditionIsAlwaysTrue) null else AnotherCondition(currentLine)
                } else {
                    NullCheckCondition(currentLine, dataEntry.name, checkType)
                }

                currentState.condition = condition
            }
            else -> throwUnsupportedOpcode(p0)
        }
        incOffset()
    }

    override fun visitIincInsn(p0: Int, p1: Int) {
        checkState()
        incOffset()
    }

    private fun popAndSetNotNull() {
        // Mark variable as NotNull because instance is always necessary during invocation
        // a.getHashCode()
        // if (a == null) // prevent excess check
        val instance = currentState.pop()
        currentState.set(instance.name, DataEntry(instance.name, NullType.NotNull))
    }

    private fun checkState() {
        val cfgNode = currentState.cfgNode

        if (cfgNode != null && offset == cfgNode.end) {
            // Save state
            cfgNodeStates[cfgNode] = State(currentState, cfgNode, currentState.condition)
        }

        var resultState: State? = null

        val nextCfgNode = cfgNodes[offset]
        if (nextCfgNode != null) {
            // Restore state
            var linksCount = 0
            var firstState: State? = null
            var firstLink: CfgLink? = null

            val actualLinks = nextCfgNode.links.filter { link -> link.end == nextCfgNode }
            for (link in actualLinks) {
                val prevState = cfgNodeStates[link.begin]
                if (prevState != null) {
                    if (resultState == null) {
                        firstLink = link
                        firstState = prevState
                        resultState = State(prevState, nextCfgNode, null)
                        currentState = resultState
                    } else {
                        resultState.merge(prevState)
                    }
                }
                linksCount++
            }

            // Set state of outer block after inner return statement
            if (linksCount == 1 && resultState != null && firstState != null && firstLink != null) {
                val condition = firstState.condition
                if (condition is NullCheckCondition && condition.isDefined()) {
                    resultState.set(condition.name, DataEntry(condition.name,
                        if (firstLink.type == CfgLinkType.False) condition.nullType.invert() else condition.nullType))
                }
            }
        }
    }

    private fun throwUnsupportedOpcode(opcode: Int) {
        throw UnsupportedOperationException("Opcode $opcode is not supported")
    }
}