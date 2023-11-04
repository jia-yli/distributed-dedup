package dedup.deprecated

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.checksum._

import dedup.bloomfilter.CRC32
import dedup.bloomfilter.CRCModule

import scala.collection.mutable.ArrayBuffer

case class BloomFilterConfig(m: Int, k: Int, dataWidth: Int = 32) {
  assert(m >= 64, "m MUST >= 64")
  assert(m == (1 << log2Up(m)), "m MUST be a power of 2")
  val mWidth    = log2Up(m)
  val bramWidth = 64
  val bramDepth = m / bramWidth
}

case class BloomFilterIO(conf: BloomFilterConfig) extends Bundle {
  val initEn   = in Bool () // trigger zeroing SRAM content
  val initDone = out Bool ()
  val frgmIn   = slave Stream (Fragment(Bits(conf.dataWidth bits)))
  val res      = master Stream (Bool())
}

class BloomFilterCRC() extends Component {
//   val bfConf = BloomFilterConfig(1 << log2Up(6432424), 3) // optimal value with 4096B page size, 10GB, 50% occupacy
  val bfConf = BloomFilterConfig(1 << log2Up(131072), 3) // simulation test only, avoid long init time
  val io     = BloomFilterIO(bfConf)

  val mem        = Mem(Bits(bfConf.bramWidth bits), wordCount = bfConf.bramDepth)

  val crcSchemes = List(
    CRC32.Standard,
    CRC32.Bzip2,
    CRC32.Jamcrc,
    CRC32.C,
    CRC32.D,
    CRC32.Mpeg2,
    CRC32.Posix,
    CRC32.Xfer
  )

  val crcKernel = ArrayBuffer.empty[CRCModule]
  for (i <- 0 until bfConf.k)
    crcKernel += CRCModule(CRCCombinationalConfig(crcSchemes(i), bfConf.dataWidth bits))

  io.frgmIn.setBlocked()
  crcKernel.foreach(_.io.cmd.setIdle())

  io.res.setIdle()

  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val fsm = new StateMachine {
    val IDLE                                      = new State with EntryPoint
    val INIT, RUN_CRCINIT, RUN_NORMAL, RUN_LOOKUP, RUN_RETURN = new State

    val lookUpEn, wrBackEn = False

    val cntLookup     = Counter(bfConf.k, isActive(RUN_LOOKUP) & lookUpEn)
    val cntWrBack     = Counter(bfConf.k, isActive(RUN_LOOKUP) & wrBackEn)
    val lookupIsExist = Reg(Bool())

    val memRdAddr = UInt(log2Up(bfConf.bramDepth) bits)
    val memWrAddr = RegNext(RegNext(memRdAddr)) // two cycle rd latency
    val rBitRdAddr = Reg(UInt(log2Up(bfConf.bramWidth) bits)).init(0)
    val memWrData = Bits(bfConf.bramWidth bits)

    val memWrInitEn = False
    val cntMemInit = Counter(log2Up(bfConf.bramDepth) bits, memWrInitEn)

    memRdAddr.clearAll()
    memWrData.clearAll()

    val cntLookupIsOverflowed = Reg(Bool()).init(False)

    IDLE.whenIsActive {
      when(io.initEn)(goto(INIT))
    }

    INIT.whenIsActive {
      rInitDone := False
      memWrInitEn   := True
      when(cntMemInit.willOverflow) {
        rInitDone := True
        goto(RUN_CRCINIT)
      }
    }

    RUN_CRCINIT.whenIsActive {
      // init logic
      crcKernel.foreach { x =>
        x.io.cmd.valid := True
        x.io.cmd.data  := 0
        x.io.cmd.mode  := CRCCombinationalCmdMode.INIT
      }
      when(io.initEn)(goto(INIT)) otherwise (goto(RUN_NORMAL))
    }

    RUN_NORMAL.whenIsActive {
      // logic
      io.frgmIn.ready := True
      crcKernel.foreach { x =>
        x.io.cmd.valid := io.frgmIn.valid
        x.io.cmd.data  := io.frgmIn.fragment
        x.io.cmd.mode  := CRCCombinationalCmdMode.UPDATE
      }
      when(io.initEn)(goto(INIT)) otherwise {
        when(io.frgmIn.isLast & io.frgmIn.fire)(goto(RUN_LOOKUP))
      }
    }

    RUN_LOOKUP.onEntry {
      lookupIsExist := True
    }

    RUN_LOOKUP.whenIsActive {
      cntLookupIsOverflowed.setWhen(cntLookup.willOverflow)
      lookUpEn := ~cntLookupIsOverflowed
      val lookUpVld = RegNext(RegNext(lookUpEn)) // two cycle rd latency
      wrBackEn := lookUpVld

      val crcResVec = Vec(crcKernel.map(_.io.crc))

      memRdAddr  := crcResVec(cntLookup)(bfConf.mWidth - 1 downto log2Up(bfConf.bramWidth)).asUInt
      rBitRdAddr := crcResVec(RegNext(RegNext(cntLookup.value)))(log2Up(bfConf.bramWidth) - 1 downto 0).asUInt

      val memRdData = RegNext(mem.readSync(memRdAddr, lookUpEn)) // one reg pipeline on rd port to meet ultra RAM requirement

      when(lookUpVld) {
        lookupIsExist := lookupIsExist & memRdData(rBitRdAddr)
      }

      memWrData := memRdData | (1 << rBitRdAddr) // bitwise

      when(io.initEn)(goto(INIT)) otherwise {
        when(cntWrBack.willOverflow) (goto(RUN_RETURN))
      }

    }

    RUN_LOOKUP.onExit {
      cntLookupIsOverflowed.clear()
    }

    RUN_RETURN.whenIsActive {
      io.res.payload := lookupIsExist
      io.res.valid := True
      when(io.initEn)(goto(INIT)) otherwise {
        when(io.res.fire) (goto(RUN_CRCINIT))
      }
    }

  }

  /** mux write cmd here to avoid multi-port memory for WR */
  val memWrEn = fsm.memWrInitEn | (fsm.isActive(fsm.RUN_LOOKUP) & fsm.wrBackEn)
  val memWrAddress = fsm.isActive(fsm.INIT) ? fsm.cntMemInit.value | fsm.memWrAddr
  val memWrD = fsm.isActive(fsm.INIT) ? B(0, bfConf.bramWidth bits) | fsm.memWrData
  mem.write(memWrAddress, memWrD, memWrEn)

}