package kr.pe.ecmaxp.thumbsk

import kr.pe.ecmaxp.thumbsk.exc.InvalidAddressArmException
import kr.pe.ecmaxp.thumbsk.exc.InvalidMemoryException
import kr.pe.ecmaxp.thumbsk.exc.UnexceptedLogicError
import kr.pe.ecmaxp.thumbsk.exc.UnsupportedInstructionException
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.FC
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.FN
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.FQ
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.FV
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.FZ
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I0
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I1
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I10
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I11
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I12
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I7
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I8
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.I9
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L1
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L10
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L11
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L2
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L3
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L4
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L5
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L7
import kr.pe.ecmaxp.thumbsk.helper.BitConsts.L8
import kr.pe.ecmaxp.thumbsk.helper.RegisterIndex.CPSR
import kr.pe.ecmaxp.thumbsk.helper.RegisterIndex.LR
import kr.pe.ecmaxp.thumbsk.helper.RegisterIndex.PC
import kr.pe.ecmaxp.thumbsk.helper.RegisterIndex.SP
import kr.pe.ecmaxp.thumbsk.signal.ControlPauseSignal
import kr.pe.ecmaxp.thumbsk.signal.ControlStopSignal

private const val UINT_MAX = 0xFFFFFFFFL


@Suppress("LocalVariableName", "RedundantGetter", "RedundantSetter")
public class CPU {
    var regs = Registers()
        get() = field
        set(value) {
            field = value
        }

    var memory = Memory()
        get() = field
        set(value) {
            field = value
        }

    var interruptHandler: InterruptHandler? = null
        get() = field
        set(value) {
            field = value
        }

    private var executedCount = 0
    private var buffer: ShortArray? = null

    @Throws(InvalidMemoryException::class, UnknownInstructionException::class, InvalidAddressArmException::class, ControlPauseSignal::class, ControlStopSignal::class)
        fun run(inst_count: Int) {
            var count = inst_count
            val memory = this.memory
            var REGS = regs.load()

            var increase_pc: Boolean = true
            var q = REGS[CPSR] and FQ != 0
            var v = REGS[CPSR] and FV != 0
            var c = REGS[CPSR] and FC != 0
            var z = REGS[CPSR] and FZ != 0
            var n = REGS[CPSR] and FN != 0
            var pc = REGS[PC]

            assert(pc and I0 == 0)
            val region = memory.findRegion(pc.toLong(), 2)
            if (buffer == null) {
                buffer = ShortArray(region.size / 2)
                for (i in 0 until buffer!!.size step 2)
                    buffer!![i] = memory.fetchCode(region.begin.toInt() + i).toShort()
            }

            val base = region.begin.toInt()

            pc = pc and I0.inv()

            val totalCount = count

            try {
                while (count-- > 0) {
                    // val code = memory.fetchCode(pc)
                    val code = buffer!![pc - base].toInt() and 0xFFFF

                    when (code shr 12 and L4) {
                        0, 1 -> { // :000x
                            var Rs = code shr 3 and L3
                            var Rd = code and L3
                            var left = REGS[Rs]
                            var value: Int

                            when (code shr 11 and L2) { // move shifted register
                                0 -> { // :00000 ; LSL Rd, Rs, #Offset5
                                    val right = code shr 6 and L5 // 0 ~ 31
                                    value = left shl right

                                    if (right > 0)
                                        c = left shl right - 1 and FN != 0
                                }
                                1 -> { // :00001 ; LSR Rd, Rs, #Offset5
                                    val right = code shr 6 and L5 // 1 ~ 32
                                    if (right == 0) {
                                        value = 0
                                        c = left and FN != 0
                                    } else {
                                        value = left ushr right
                                        c = left and (1 shl right - 1) != 0
                                    }
                                }
                                2 -> { // :00010 ; ASR Rd, Rs, #Offset5
                                    val right = code shr 6 and L5 // 1 ~ 32
                                    if (right == 0) {
                                        value = if (left > 0) 0 else -1
                                        c = left and FN != 0
                                    } else {
                                        value = left shr right
                                        c = left and (1 shl right - 1) != 0
                                    }
                                }
                                3 -> { // :00011 ; add/subtract
                                    val I = code shr 10 and 1 != 0
                                    val Rn = code shr 6 and L3
                                    Rs = code shr 3 and L3
                                    Rd = code and L3
                                    left = REGS[Rs]
                                    val right = if (I) Rn else REGS[Rn]

                                    when (code shr 9 and L1) {
                                        0 -> { // :0001100 | :0001110 ; ADD Rd, Rs, Rn | ADD Rd, Rs, #Offset3
                                            val Lleft = Integer.toUnsignedLong(left)
                                            val Lright = Integer.toUnsignedLong(right)
                                            val Lvalue = Lleft + Lright;
                                            value = Lvalue.toInt()
                                            c = Lvalue > UINT_MAX
                                            v = (left xor value) and (right xor value) < 0
                                        }
                                        1 -> { // :0001101 | :0001111 ; SUB Rd, Rs, Rn | SUB Rd, Rs, #Offset3
                                            val Lleft = Integer.toUnsignedLong(left)
                                            val LIright = Integer.toUnsignedLong(right.inv())
                                            val Lvalue = Lleft + LIright + 1L
                                            value = Lvalue.toInt()
                                            c = Lvalue > UINT_MAX
                                            v = (left xor right) and (left xor value) < 0
                                        }
                                        else -> throw UnexceptedLogicError()
                                    }
                                }
                                else -> throw UnexceptedLogicError()
                            }

                            n = value < 0
                            z = value == 0
                            REGS[Rd] = value
                        }
                        2, 3 -> { // :001 ; move/compare/add/subtract immediate
                            val Rd = code shr 8 and L3
                            val left = REGS[Rd]
                            val right = code and L8
                            var value: Int
                            var Lvalue: Long

                            when (code shr 11 and L2) {
                                0 -> { // :001100 ; MOV Rd, #Offset8
                                    value = right
                                    REGS[Rd] = value
                                }
                                1 -> { // :001101 ; CMP Rd, #Offset8
                                    Lvalue = (Integer.toUnsignedLong(left)) +
                                            (Integer.toUnsignedLong(right.inv())) + 1L
                                    value = Lvalue.toInt()
                                    // only compare (no write)
                                    c = Lvalue > UINT_MAX
                                    v = left xor right and (left xor value) < 0
                                }
                                2 -> { // :001110 ; ADD Rd, #Offset8
                                    Lvalue = (Integer.toUnsignedLong(left)) + (Integer.toUnsignedLong(right))
                                    value = Lvalue.toInt()
                                    REGS[Rd] = value
                                    c = Lvalue > UINT_MAX
                                    v = left xor value and (right xor value) < 0
                                }
                                3 -> { // :001111 ; SUB Rd, #Offset8
                                    Lvalue = (Integer.toUnsignedLong(left)) +
                                            (Integer.toUnsignedLong(right.inv())) + 1L
                                    value = Lvalue.toInt()
                                    REGS[Rd] = value
                                    c = Lvalue > UINT_MAX
                                    v = left xor right and (left xor value) < 0
                                }
                                else -> throw UnexceptedLogicError()
                            }

                            n = value < 0
                            z = value == 0
                        }
                        4 -> // :0100
                            when (code shr 10 and L2) {
                                0 -> { // :010000 ; ALU operations
                                    val Rs = code shr 3 and L3
                                    val Rd = code and L3
                                    val left = REGS[Rd]
                                    var right = REGS[Rs]
                                    val value: Int
                                    val Lvalue: Long

                                    when (code shr 6 and L4) {
                                        0 -> { // :0100000000 ; AND Rd, Rs ; Rd:= Rd AND Rs
                                            value = left and right
                                            REGS[Rd] = value
                                        }
                                        1 -> { // :0100000001 ; EOR Rd, Rs ; Rd:= Rd EOR Rs
                                            value = left xor right
                                            REGS[Rd] = value
                                        }
                                        2 -> { // :0100000010 ; LSL Rd, Rs ; Rd := Rd << Rs
                                            when {
                                                right >= 32 -> {
                                                    value = 0
                                                    c = right == 32 && left and 1 != 0
                                                }
                                                right < 0 -> {
                                                    value = 0
                                                    c = false
                                                }
                                                right == 0 -> value = left
                                                else -> {
                                                    value = left shl right
                                                    c = left shl right - 1 and FN != 0
                                                }
                                            }

                                            REGS[Rd] = value
                                        }
                                        3 -> { // :0100000011 ; LSR Rd, Rs ; Rd := Rd >>> Rs
                                            when {
                                                right >= 32 -> {
                                                    value = 0
                                                    c = right == 32 && left and FN != 0
                                                }
                                                right < 0 -> {
                                                    value = 0
                                                    c = false
                                                }
                                                right == 0 -> value = left
                                                else -> {
                                                    value = left ushr right
                                                    REGS[Rd] = value
                                                    c = (left ushr (right - 1)) and 1 != 0
                                                }
                                            }
                                        }
                                        4 -> // :0100000100 ; ASR Rd, Rs ; Rd := Rd ASR Rs
                                            if (right < 0 || right >= 32) {
                                                value = if (left > 0) 0 else -1
                                                c = value < 0
                                            } else if (right == 0) {
                                                value = left
                                            } else {
                                                value = left shr right
                                                REGS[Rd] = value
                                                c = left and (1 shl right - 1) != 0
                                            }
                                        5 -> { // :0100000101 ; ADC Rd, Rs ; Rd := Rd + Rs + C-bit
                                            Lvalue = (Integer.toUnsignedLong(left)) +
                                                    (Integer.toUnsignedLong(right)) + if (c) 1L else 0L
                                            value = Lvalue.toInt()
                                            REGS[Rd] = value

                                            c = Lvalue != value.toLong()
                                            v = left > 0 && right > 0 && value < 0 || left < 0 && right < 0 && value > 0
                                        }
                                        6 -> { // :0100000110 ; SBC Rd, Rs ; Rd := Rd - Rs - NOT C-bit
                                            Lvalue = left.toLong() - right.toLong() - if (c) 0L else 1L
                                            value = left - right - if (c) 0 else 1
                                            REGS[Rd] = value

                                            c = c || value < 0
                                            v = Lvalue != value.toLong()
                                        }
                                        7 -> { // :0100000111 ; ROR Rd, Rs ; Rd := Rd ROR Rs
                                            right = right and 31
                                            value = left.ushr(right) or (left shl 32 - right)
                                            c = left.ushr(right - 1) and I0 != 0
                                            REGS[Rd] = value
                                        }
                                        8 -> // :0100001000 ; TST Rd, Rs ; set condition codes on Rd AND Rs
                                            value = left and right
                                        9 -> { // :0100001001 ; NEG Rd, Rs ; Rd = -Rs
                                            Lvalue = (Integer.toUnsignedLong(right.inv())) + 1L
                                            value = Lvalue.toInt()
                                            REGS[Rd] = value
                                            c = Lvalue > UINT_MAX
                                            v = right and value < 0
                                        }
                                        10 -> { // :0100001010 ; CMP Rd, Rs ; set condition codes on Rd - Rs
                                            Lvalue = (Integer.toUnsignedLong(left)) +
                                                    (Integer.toUnsignedLong(right.inv())) + 1L
                                            value = Lvalue.toInt()
                                            c = Lvalue > UINT_MAX
                                            v = left xor right and (left xor value) < 0
                                        }
                                        11 -> { // :0100001011 ; CMN Rd, Rs ; set condition codes on Rd + Rs
                                            Lvalue = (Integer.toUnsignedLong(left)) + (Integer.toUnsignedLong(right))
                                            value = Lvalue.toInt()
                                            c = Lvalue > UINT_MAX
                                            v = left xor value and (right xor value) < 0
                                        }
                                        12 -> { // :0100001100 ; ORR Rd, Rs ; Rd := Rd OR Rs
                                            value = left or right
                                            REGS[Rd] = value
                                        }
                                        13 -> { // :0100001101 ; MUL Rd, Rs ; Rd := Rs * Rd
                                            val svalue = left.toLong() * right.toLong()
                                            value = left * right
                                            REGS[Rd] = value
                                            c = c or (value.toLong() != svalue) // ???
                                            v = false // svalue != value?
                                        }
                                        14 -> { // :0100001110 ; BIC Rd, Rs ; Rd := Rd AND NOT Rs
                                            value = left and right.inv()
                                            REGS[Rd] = value
                                        }
                                        15 -> { // :0100001111 ; MVN Rd, Rs ; Rd := NOT Rs
                                            value = right.inv()
                                            REGS[Rd] = value
                                        }
                                        else -> throw UnexceptedLogicError()
                                    }

                                    n = value < 0
                                    z = value == 0
                                }
                                1 -> { // :010001 ; Hi register operations/branch exchange
                                    val H1 = code shr 7 and L1 != 0
                                    val H2 = code shr 6 and L1 != 0
                                    val Rd = (code and L3) + if (H1) 8 else 0
                                    val Rs = (code shr 3 and L3) + if (H2) 8 else 0

                                    REGS[PC] = pc

                                    when (code shr 8 and L2) {
                                        0 -> { // :01000100 ; ADD Rd, Hs ; ADD Hd, Rs ; ADD Hd, Hs
                                            val left = REGS[Rd]
                                            var right = REGS[Rs]
                                            if (Rs == PC)
                                                right += 4

                                            REGS[Rd] = left + right
                                        }
                                        1 -> { // :01000101 ; CMP Rd, Hs ; CMP Hd, Rs ; CMP Hd, Hs
                                            val left = REGS[Rd]
                                            val right = REGS[Rs]
                                            val Lvalue = (Integer.toUnsignedLong(left)) +
                                                    (Integer.toUnsignedLong(right.inv())) + 1L
                                            val value = Lvalue.toInt()
                                            // only compare (no write)
                                            n = value < 0
                                            z = value == 0
                                            c = Lvalue > UINT_MAX
                                            v = left xor right and (left xor value) < 0
                                        }
                                        2 -> { // :01000110 ; MOV Rd, Hs ; MOV Hd, Rs ; MOV Hd, Hs
                                            var value = REGS[Rs]
                                            if (Rd == PC)
                                                value -= 2

                                            REGS[Rd] = value
                                        }
                                        3 -> { // :01000111 ; BX Rs ; BX Hs
                                            val value = REGS[Rs]
                                            if (value and I0 != 1)
                                                throw UnknownInstructionException()

                                            if (H1)
                                                REGS[LR] = pc + 2 or I0

                                            REGS[PC] = value and I0.inv()
                                            increase_pc = false
                                        }
                                        else -> throw UnexceptedLogicError()
                                    }

                                    pc = REGS[PC]
                                }
                                2, 3 -> { // :01001 ; PC-relative load ; LDR Rd, [PC, #Imm]
                                    val Rd = code shr 8 and L3
                                    var addr = code and L8 shl 2
                                    addr += pc + 4 and I1.inv()

                                    REGS[Rd] = memory.readInt(addr)
                                }
                            }
                        5 -> { // :0101
                            if (code and I9 == 0) { // :0101xx0 ; load/store with register offset
                                val L = code and I11 != 0
                                val B = code and I10 != 0
                                val Ro = code shr 6 and L3
                                val Rb = code shr 3 and L3
                                val Rd = code and L3
                                val addr = REGS[Rb] + REGS[Ro]

                                if (L) {
                                    if (B) { // :0101100 ; LDRB Rd, [Rb, Ro]
                                        REGS[Rd] = memory.readByte(addr).toInt() and 0xFF
                                    } else { // :0101100 ; LDR Rd, [Rb, Ro]
                                        REGS[Rd] = memory.readInt(addr)
                                    }
                                } else {
                                    if (B) { // :0101010 ; STRB Rd, [Rb, Ro]
                                        memory.writeByte(addr, REGS[Rd].toByte())
                                    } else { // :0101000 ; STR Rd, [Rb, Ro]
                                        memory.writeInt(addr, REGS[Rd])
                                    }
                                }
                            } else { // :0101xx1 ; load/store sign-extended byte/halfword
                                val H = code and I11 != 0
                                val S = code and I10 != 0
                                val Ro = code shr 6 and L3
                                val Rb = code shr 3 and L3
                                val Rd = code and L3
                                val addr = REGS[Rb] + REGS[Ro]

                                if (S) {
                                    REGS[Rd] = if (H) { // :0101111 ; LDSH Rd, [Rb, Ro]
                                        memory.readShort(addr).toInt()
                                    } else { // :0101011 ; LDSB Rd, [Rb, Ro]
                                        memory.readByte(addr).toInt()
                                    }
                                } else {
                                    if (H) { // :0101101 ; LDRH Rd, [Rb, Ro]
                                        val value = memory.readShort(addr).toInt()
                                        REGS[Rd] = value
                                    } else { // :0101001 ; STRH Rd, [Rb, Ro]
                                        val value = REGS[Rd]
                                        memory.writeShort(addr, value.toShort())
                                    }
                                }
                            }
                        }
                        6, 7 -> { // :011 ; load/store with immediate offset
                            val B = code and I12 != 0
                            val L = code and I11 != 0
                            val Rb = code shr 3 and L3
                            val Rd = code and L3
                            var offset = code shr 6 and L5

                            if (!B)
                                offset = offset shl 2

                            val addr = REGS[Rb] + offset

                            if (L) {
                                val value = if (!B) // :01111 ; LDR Rd, [Rb, #Imm]
                                    memory.readInt(addr)
                                else // :01101 ; LDRB Rd, [Rb, #Imm]
                                    memory.readByte(addr).toInt() and 0xFF

                                REGS[Rd] = value
                            } else {
                                val value = REGS[Rd]

                                if (!B) // :01100 ; STR Rd, [Rb, #Imm]
                                    memory.writeInt(addr, value)
                                else // :01110 ; STRB Rd, [Rb, #Imm]
                                    memory.writeByte(addr, value.toByte())
                            }
                        }
                        8 -> { // :1000x ; load/store halfword
                            val L = code and I11 != 0
                            val Rb = code shr 3 and L3
                            val Rd = code and L3
                            val value = code shr 6 and L5 shl 1
                            val addr = REGS[Rb] + value

                            if (L) // :10001 ; LDRH Rd, [Rb, #Imm]
                                REGS[Rd] = memory.readShort(addr).toInt() and 0xFFFF
                            else // :10000 ; STRH Rd, [Rb, #Imm]
                                memory.writeShort(addr, REGS[Rd].toShort())
                        }
                        9 -> { // :1001x ; SP-relative load/store
                            val L = code and I11 != 0
                            val Rd = code shr 8 and L3
                            val value = code and L8 shl 2
                            val addr = REGS[SP] + value

                            if (L) // :10011 ; LDR Rd, [SP, #Imm]
                                REGS[Rd] = memory.readInt(addr)
                            else // :10010 ; STR Rd, [SP, #Imm]
                                memory.writeInt(addr, REGS[Rd])
                        }
                        10 -> { // :1010x ; load address
                            val fSP = code and I11 != 0
                            val Rd = code shr 8 and L3
                            var value = code and L8 shl 2

                            value += if (fSP) // :10101 ; ADD Rd, SP, #Imm
                                REGS[SP]
                            else // :10100 ; ADD Rd, PC, #Imm
                                pc + 4 and I1.inv()

                            REGS[Rd] = value
                        }
                        11 -> { // :1011
                            when (code shr 8 and L4) {
                                0 -> { // :10110000x ; add offset to Stack Pointer
                                    val S = code and I7 != 0
                                    val value = code and L7 shl 2

                                    if (S) // :101100000 ; ADD SP, #-Imm
                                        REGS[SP] -= value
                                    else // :101100001 ; ADD SP, #Imm
                                        REGS[SP] += value
                                }
                                1 -> { // :10110001 ; CBZ Rd, #Imm
                                    val Rd = code and L3
                                    val offset = (code shr 4 and L5 shl 1) + 4

                                    if (REGS[Rd] == 0) {
                                        pc += offset
                                        increase_pc = false
                                    }
                                }
                                2 -> { // :10110010 ; SXTH, SXTB, UXTH, UXTB
                                    val Rs = code shr 3 and L3
                                    val Rd = code and L3
                                    val left = REGS[Rs]

                                    val value = when (code shr 6 and L2) {
                                        0 -> // :1011001000 ; SXTH Rd, Rs
                                            left.toShort().toInt()
                                        1 -> // :1011001001 ; SXTB Rd, Rs
                                            left.toByte().toInt()
                                        2 -> // :1011001010 ; UXTH Rd, Rs
                                            left and 0xFFFF
                                        3 -> // :1011001011 ; UXTB Rd, Rs
                                            left and 0XFF
                                        else -> throw UnexceptedLogicError()
                                    }

                                    REGS[Rd] = value
                                }
                                3 -> { // :10110011 ; CBZ Rd, #Imm
                                    val Rd = code and L3
                                    val offset = (code shr 4 and L5 shl 1) + 4 + 0x40

                                    if (REGS[Rd] == 0) {
                                        pc += offset
                                        increase_pc = false
                                    }
                                }
                                4, 5 -> { // :1011010x ; push/pop registers
                                    val R = code and I8 != 0
                                    val list = code and L8
                                    var addr = REGS[SP]

                                    try {
                                        if (R) // :10110101 ; PUSH { ..., LR }
                                        {
                                            addr -= 4
                                            memory.writeInt(addr, REGS[LR])
                                        }

                                        // PUSH { Rlist }
                                        for (i in 7 downTo 0) {
                                            if (list and (1 shl i) != 0) {
                                                addr -= 4
                                                memory.writeInt(addr, REGS[i])
                                            }
                                        }
                                    } finally {
                                        REGS[SP] = addr
                                    }
                                }
                                6, 7, 8 -> throw UnknownInstructionException() // :10110110 :10110111 :10111000
                                9 -> { // :10111001 ; CBNZ Rd, #Imm
                                    val Rd = code and L3
                                    val offset = (code shr 4 and L5 shl 1) + 4

                                    if (REGS[Rd] != 0) {
                                        pc += offset
                                        increase_pc = false
                                    }
                                }
                                10 -> { // :10111010xx
                                    val Rs = code shr 3 and L3
                                    val Rd = code and L3
                                    var value = REGS[Rs]

                                    when (code shr 6 and L2) {
                                        0 -> // :1011101000 ; REV Rd, Rs
                                            value = value.ushr(24) and 0xFF or (
                                                    value.ushr(16) and 0xFF shl 8) or (
                                                    value.ushr(8) and 0xFF shl 16) or
                                                    (value and 0xFF shl 24)
                                        1 -> // :1011101001 ; REV16 Rd, Rs
                                            throw UnsupportedInstructionException()
                                        2 -> // :1011101010 ; INVALID
                                            throw UnknownInstructionException()
                                        3 -> // :1011101011 ; REVSH Rd, Rs
                                            throw UnsupportedInstructionException()
                                        else -> throw UnexceptedLogicError()
                                    }

                                    REGS[Rd] = value
                                }
                                11 -> { // :10111011 ; CBNZ Rd, #Imm
                                    val Rd = code and L3
                                    val offset = (code shr 4 and L5 shl 1) + 4 + 0x40

                                    if (REGS[Rd] != 0) {
                                        pc += offset
                                        increase_pc = false
                                    }
                                }
                                12, 13 -> { // :1011110x ; push/pop registers
                                    val R = code and I8 != 0
                                    val list = code and L8
                                    var addr = REGS[SP]

                                    try {
                                        // POP { Rlist }
                                        for (i in 0..7) {
                                            if (list and (1 shl i) != 0) {
                                                REGS[i] = memory.readInt(addr)
                                                addr += 4
                                            }
                                        }

                                        if (R) { // :10111101 {..., PC} ; POP { ..., PC }
                                            val value = memory.readInt(addr)
                                            if (value and I0 != 1)
                                                throw InvalidAddressArmException()

                                            pc = value and I0.inv()
                                            addr += 4
                                            increase_pc = false
                                        }
                                    } finally {
                                        REGS[SP] = addr
                                    }
                                }
                                14, 15 -> // :10111110 :10111111
                                    throw UnknownInstructionException()
                                else -> throw UnexceptedLogicError()
                            }
                        }
                        12 -> { // :1100 ; multiple load/store
                            val L = code and I11 != 0
                            val list = code and L8
                            val Rb = code shr 8 and L3
                            var addr = REGS[Rb]

                            try {
                                if (!L) { // :11001 ; STMIA Rb!, { Rlist }
                                    for (i in 0..7)
                                        if (list and (1 shl i) != 0) {
                                            memory.writeInt(addr, REGS[i])
                                            addr += 4
                                        }
                                } else { // :11000 ; LDMIA Rb!, { Rlist }
                                    for (i in 0..7)
                                        if (list and (1 shl i) != 0) {
                                            REGS[i] = memory.readInt(addr)
                                            addr += 4
                                        }
                                }
                            } finally {
                                REGS[Rb] = addr
                            }
                        }
                        13 -> { // :1101 ; conditional branch (or software interrupt)
                            val soffset = (code and L8).toByte()
                            val cond = when (code shr 8 and L4) {
                                0 -> z
                                // :11010000 ; BEQ label

                                1 -> !z
                                // :11010001 ; BNE label

                                2 -> c
                                // :11010010 ; BCS label

                                3 -> !c
                                // :11010011; BCC label

                                4 -> n
                                // :11010100 ; BMI label

                                5 -> !n
                                // :11010101 ; BPL label

                                6 -> v
                                // :11010110 ; BVS label

                                7 -> !v
                                // :11010111 ; BVC label

                                8 -> c && !z
                                // :11011000 ; BHI label

                                9 -> !c || z
                                // :11011001 ; BLS label

                                10 -> n == v
                                // :11011010 ; BGE label ; (n && v) || (!n && !v)

                                11 -> n != v
                                // :11011011 ; BLT label ; (n && !v) || (!n && v)

                                12 -> !z && n == v
                                // :11011100 ; BGT label ; !z && (n && v || !n && !v)

                                13 -> z || n != v
                                // :11011101 ; BLE label ; z || (n && !v) || (!n && v)

                                14 -> throw UnknownInstructionException() // :11011110
                                15 -> { // :11011111 ; software interrupt
                                    // SWI Value8

                                    REGS[PC] = pc
                                    REGS[CPSR] = (if (q) FQ else 0) or
                                            (if (v) FV else 0) or
                                            (if (c) FC else 0) or
                                            (if (z) FZ else 0) or
                                            if (n) FN else 0

                                    regs.store(REGS)

                                    try {
                                        interruptHandler!!.invoke(soffset.toInt() and 0xFF)
                                    } catch (e: ControlPauseSignal) {
                                        throw e
                                    } catch (e: ControlStopSignal) {
                                        pc += 2
                                        throw e
                                    } finally {
                                        REGS = regs.load()
                                        pc = REGS[PC]
                                        q = REGS[CPSR] and FQ != 0
                                        v = REGS[CPSR] and FV != 0
                                        c = REGS[CPSR] and FC != 0
                                        z = REGS[CPSR] and FZ != 0
                                        n = REGS[CPSR] and FN != 0
                                    }

                                    false // this is not jump
                                }
                                else -> throw UnexceptedLogicError()
                            }

                            if (cond) {
                                var value = soffset.toInt() and L8 shl 1
                                if (value and I8 != 0) {
                                    value = value or L8.inv()
                                }

                                pc += 4 + value
                                increase_pc = false
                            }
                        }
                        14 -> { // :11100 ; unconditional branch
                            if (code and I11 != 0)
                                throw UnknownInstructionException()

                            var value = code and L10 shl 1
                            if (code and I10 != 0) {
                                value = value or L11.inv()
                            }

                            pc += 4 + value
                            increase_pc = false
                        }
                        15 -> { // :1111 ; long branch with link
                            val H = code shr 11 and L1 != 0
                            val value = code and L11
                            if (!H) {
                                REGS[LR] = value shl 12
                                count++
                            } else {
                                var addr = REGS[LR]
                                addr = addr or (value shl 1)
                                if (addr and (1 shl 22) != 0)
                                    addr = addr or 8388607.inv()

                                val lr = pc
                                pc = lr + addr + 2
                                REGS[LR] = lr + 3
                                increase_pc = false
                            }
                        }
                        else -> throw UnknownInstructionException()
                    } // CPSR condition are unaffected

                    if (increase_pc) {
                        pc += 2
                    } else {
                        increase_pc = true
                    }
                }
            } finally {
                REGS[PC] = pc
                REGS[CPSR] = (if (q) FQ else 0) or
                        (if (v) FV else 0) or
                        (if (c) FC else 0) or
                        (if (z) FZ else 0) or
                        if (n) FN else 0

                regs.store(REGS)
                executedCount += totalCount - count
            }
    }
}
