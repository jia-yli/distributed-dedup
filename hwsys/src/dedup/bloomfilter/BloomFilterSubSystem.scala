package dedup
package bloomfilter

import spinal.core._
import spinal.lib._

case class BloomFilterConfig(m: Int, k: Int, dataWidth: Int = 32) {
  val readyQueueLogDepth = 3
  val waitingQueueLogDepth = 3
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)
  assert(m >= 64, "m MUST >= 64")
  assert(m == (1 << log2Up(m)), "m MUST be a power of 2")
  val mWidth    = log2Up(m)
  val bramWidth = 64
  val bramDepth = m / bramWidth
}

case class BloomFilterSSIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool () 
  val initDone     = out Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val res          = master Stream (BloomFilterLookupFSMRes())
}

/* 
  To add instruction path to bloom filter, the previous bloomfilter is restructured to bloom filter subsystem
  The first half is the CRC calculation and instruction processing
  Data will be send to CRC Kernel and the results will be cached in CRCResQueue
  512 bits raw instructions will be decoded by the decoder:
    write -> opCode = write, length(number of pages) -> waiting for CRC, go to decodedWaitingInstrQueue
    erase -> opCode = erase, CRC -> ready to issue to FSM, go to decodedReadyInstrQueue
    read -> throw
  Issuer will combine CRC and write instruction or select a ready instruction and give to FSM.
  Now the strategy is issued instruction are in the same order as input to the decoder
  
  The second half is the Bloom Filter lookup logic, lookup FSM
  I break the old FSM into CRC and lookup FSM, no big changes in FSM
  The FSM accepts (opCode, CRC) format of instruction from outside.
*/
class BloomFilterSubSystem(conf : DedupConfig) extends Component {
  val io     = BloomFilterSSIO(conf)

  val bfConf = conf.bfConf
  
  val CRCKernel = new CRCKernelWrap(bfConf)

  val CRCResQueue = StreamFifo(Vec(Bits(32 bits), bfConf.k), 4)
  
  val instrDecoder = new BloomFilterInstrDecoder(conf)

  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf), 8)

  val decodedReadyInstrQueue = StreamFifo(DecodedReadyInstr(conf), 8)

  val instrIssuer = new BloomFilterInstrIssuer(conf)

  val bfLookupFSM = new BloomFilterLookupFSM(bfConf)
  
  // initialization
  // io.opStrmIn.setBlocked()
  // io.res.setIdle()

  io.initDone := bfLookupFSM.io.initDone

  // CRC Kernel + CRC res Queue
  CRCKernel.io.initEn := io.initEn
  CRCKernel.io.frgmIn.translateFrom(io.pgStrmFrgmIn)((a, b) => {
    /** use the lsb 32b of the input 512b for CRC */
    a.fragment := b.fragment(bfConf.dataWidth-1 downto 0)
    a.last := b.last
  })

  CRCResQueue.io.push  << CRCKernel.io.res
  CRCResQueue.io.flush := io.initEn

  // decoder + decoded instr Queue
  io.opStrmIn                        >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream   >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream >> decodedWaitingInstrQueue.io.push

  decodedReadyInstrQueue.io.flush    := io.initEn
  decodedWaitingInstrQueue.io.flush  := io.initEn

  // instr issuer and BFFSM
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop
  instrIssuer.io.CRCResStream        << CRCResQueue.io.pop

  instrIssuer.io.instrIssueStream    >> bfLookupFSM.io.instrStrmIn

  bfLookupFSM.io.initEn              := io.initEn
  bfLookupFSM.io.res                 >> io.res
}

