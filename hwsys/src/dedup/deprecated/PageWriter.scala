package dedup.deprecated

import spinal.core.Component.push
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import util.{CntDynmicBound, FrgmDemux}

case class PageWriterConfig(dataWidth: Int = 512, ptrWidth: Int = 64) {
  val pgIdxWidth = 32
  val pgPtrWidth = 64
  val pgByteSize = 4096
  val pgAddBitShift = log2Up(pgByteSize)
  val pgWordCnt = pgByteSize / (dataWidth/8)

  val pgIdxFifoSize = 256 // no fence on this stream flow, always assume there's enough space in idxFifo
  val pgBufSize = 64 * pgWordCnt

  val frgmType = Bits(dataWidth bits)
}

case class PageWriterResp(conf: PageWriterConfig) extends Bundle {
  val pgIdx = UInt(conf.pgIdxWidth bits)
  val pgPtr = UInt(conf.ptrWidth bits)
  val isExist = Bool()
}

case class RespPtr(conf: PageWriterConfig) extends Bundle {
  val pgPtr = UInt(conf.ptrWidth bits)
  val isExist = Bool()
}

case class PageWriterIO(conf: PageWriterConfig) extends Bundle {
  val initEn = in Bool()

  val frgmIn   = slave Stream (Fragment(Bits(conf.dataWidth bits)))
  val res = master Stream (PageWriterResp(conf))
  /** interface bloomFilter that priliminarily filters the fragments*/
  val bfRes = slave Stream (Bool())
  /** interface hash table that stores the SHA3 entry of each page */
  val ptrStrm1, ptrStrm2 = master Stream(UInt(conf.ptrWidth bits))
  val lookupRes = slave Stream (HashTabResp(conf.ptrWidth))
  /** interfae storage */
  // val axiConf = Axi4ConfigAlveo.u55cHBM
  // val axiStore = master(Axi4(axiConf))
  /** bandwidth controller */
  val factorThrou = in UInt(5 bits)
}


class PageWriter(conf: PageWriterConfig) extends Component {

  val io = PageWriterIO(conf)
  /** queue here to avoid blocking the wrap pgIn, which is also forked to BF & SHA3 */
  val frgmInQ = io.frgmIn.queue(512)
  val bfResQ = io.bfRes.queue(8)
  val pgIdxCnt = Counter(conf.pgIdxWidth bits, frgmInQ.lastFire)
  val pgIdxGen = Stream(UInt(conf.pgIdxWidth bits))
  pgIdxGen.payload := pgIdxCnt
  pgIdxGen.valid := True // always valid
  val pgIdxStrms = StreamDemux(pgIdxGen.continueWhen(bfResQ.fire), bfResQ.payload.asUInt, 2).map(_.queue(conf.pgIdxFifoSize))

  bfResQ.continueWhen(frgmInQ.lastFire).freeRun()


  /** demux1 with bloom filter res, T: PgBuffer, F: PgWr */
  val frgmDemux1 =  FrgmDemux(2, Bits(conf.dataWidth bits))
  frgmDemux1.io.sel := bfResQ.payload.asUInt
  frgmDemux1.io.en := bfResQ.valid
  frgmDemux1.io.strmI << frgmInQ
  /** two pgWr path (fragment) */
  val pgGoThro = frgmDemux1.io.strmO(0)
  val pgBuffer = frgmDemux1.io.strmO(1).queue(conf.pgBufSize)
  /** demux2 with ht lookup res, T: PgThrow, F: PgWr */
  val frgmDemux2 = FrgmDemux(2, Bits(conf.dataWidth bits))
  frgmDemux2.io.sel := io.lookupRes.isExist.asUInt
  frgmDemux2.io.en := io.lookupRes.valid
  frgmDemux2.io.strmI << pgBuffer

  val pgWr = frgmDemux2.io.strmO(0).queue(conf.pgBufSize)
  val pgThrow = frgmDemux2.io.strmO(1)

  /** frgmArbiter, two PgWr -> pgStore FIXME: add a FIFO before store to avoid the pressure */
  val pgStore = StreamFragmentArbiter(conf.frgmType)(Vec(pgGoThro, pgWr))

  /** store ptr generator*/
  val storePtr = Counter(conf.pgPtrWidth bits, pgStore.lastFire)

  /** resPtr streams */
  val resPtrQGoThro, resPtrQBuffer = StreamFifo(RespPtr(conf), 8)
  resPtrQGoThro.io.push.payload.pgPtr := (storePtr << conf.pgAddBitShift).resized
  resPtrQGoThro.io.push.payload.isExist := False
  resPtrQGoThro.io.push.valid := pgGoThro.lastFire

  resPtrQBuffer.io.push.payload.pgPtr := io.lookupRes.isExist ? io.lookupRes.dupPtr | (storePtr << conf.pgAddBitShift).resized
  resPtrQBuffer.io.push.payload.isExist := io.lookupRes.isExist
  resPtrQBuffer.io.push.valid := pgBuffer.lastFire

  io.lookupRes.continueWhen(pgBuffer.lastFire).freeRun()

  val resJoinPgGoThro = StreamJoin(pgIdxStrms(0), resPtrQGoThro.io.pop)
  val resJoinPgBuffer = StreamJoin(pgIdxStrms(1), resPtrQBuffer.io.pop)

  io.res.translateFrom(StreamMux(resJoinPgBuffer.valid.asUInt, Seq(resJoinPgGoThro, resJoinPgBuffer)))((a, b) => {
    a.pgIdx := b._1
    a.pgPtr := b._2.pgPtr
    a.isExist := b._2.isExist
  })

  /** ptrStrm1: pgGoThrough store (not exist in bFilter), ptrStrm2: pgBuffered store (exist in bFilter, but SHA value does NOT match) */
  val ptrStrm1Q, ptrStrm2Q = StreamFifo(UInt(conf.ptrWidth bits), 128)
  ptrStrm1Q.io.push.payload := (storePtr << conf.pgAddBitShift).resized
  ptrStrm2Q.io.push.payload := (storePtr << conf.pgAddBitShift).resized
  //FIXME: stream handshake violation
  ptrStrm1Q.io.push.valid := pgGoThro.lastFire
  ptrStrm2Q.io.push.valid := pgWr.lastFire

  io.ptrStrm1 << ptrStrm1Q.io.pop
  io.ptrStrm2 << ptrStrm2Q.io.pop

  /** page store / throw */
  pgThrow.freeRun()

  /** store throughput control */
  val cntFree = Counter(16, True) // a free running counter
  val cntStoreFire = CntDynamic(io.factorThrou+1, pgStore.fire) // fire factorThrou words every 16 clock cycle
  when(cntFree.willOverflow) (cntStoreFire.clearAll())

  when(cntStoreFire.willOverflowIfInc) (pgStore.setBlocked()) otherwise pgStore.freeRun()


  /** init */
  when(io.initEn) {
    pgIdxCnt.clear()
    storePtr.clear()
  }
}

case class CntDynamic(upBoundEx: UInt, incFlag: Bool) {
  val cnt = Reg(UInt(upBoundEx.getWidth bits)).init(0)
  val willOverflowIfInc = (cnt === upBoundEx -1)
  val willClear = False.allowOverride
  def clearAll(): Unit = willClear := True
  when(~willOverflowIfInc & incFlag) {
    cnt := cnt + 1
  }
  when(willClear) (cnt.clearAll())
}
