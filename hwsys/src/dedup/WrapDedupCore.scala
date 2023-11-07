package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.crypto.hash.sha3._

import util.Stream2StreamFragment
import scala.util.Random

import dedup.hashtable.HashTableConfig
import dedup.registerfile.RegisterFileConfig
import dedup.hashtable.HashTableSubSystem

import dedup.pagewriter.PageWriterConfig
import dedup.pagewriter.PageWriterResp
import dedup.pagewriter.SSDInstr
import dedup.pagewriter.PageWriterSubSystem

case class DedupConfig(
  /* general config */
  pgSize   : Int = 4 * 1024, // 4kiB
  wordSize : Int = 64,       // 64B

  /*instr config*/
  instrTotalWidth    : Int = 512,
  LBAWidth           : Int = 32,
  hashInfoTotalWidth : Int = 32 * 3 + 256,

  /* instr queue config */
  instrQueueLogDepth : List[Int] = List(2,6,6), // depth = 4,64,64

  /** config of submodules */
  // SHA3 
  sha3Conf : SHA3Config = SHA3Config(dataWidth = 512, sha3Type = SHA3_256, groupSize = 64),

  // RDMA dist hash table
  rfConf : RegisterFileConfig = RegisterFileConfig(tagWidth = 8,      // 256 regs in register file
                                                  nodeIdxWidth = 4    // up to 16 nodes
                                                  ),
  // 8192x4 bucket x 8 entry/bucket = 1<<18 hash table
  htConf : HashTableConfig = HashTableConfig (hashValWidth = 256, 
                                              ptrWidth = 32, 
                                              hashTableSize = (BigInt(1) << 18), 
                                              expBucketSize = 8, 
                                              hashTableOffset = (BigInt(1) << 30), 
                                              bfEnable = true,
                                              bfOptimizedReconstruct = false,
                                              sizeFSMArray = 6),

  // 1 << 27 = 8Gib/64B, for real system:
  // htConf : HashTableConfig = HashTableConfig (hashValWidth = 256, 
  //                                             ptrWidth = 32, 
  //                                             hashTableSize = (BigInt(1) << 27), 
  //                                             expBucketSize = 8, 
  //                                             hashTableOffset = (BigInt(1) << 30), 
  //                                             bfEnable = true,
  //                                             bfOptimizedReconstruct = false,
  //                                             sizeFSMArray = 6),

  pwConf : PageWriterConfig = PageWriterConfig()){
  val wordSizeBit = wordSize * 8 // 512 bit
  val pgWord = pgSize / wordSize // 64 word per page
  assert(pgSize % wordSize == 0)
}

case class WrapDedupCoreIO(conf: DedupConfig) extends Bundle {
  /** input */
  val opStrmIn = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmIn = slave Stream (Bits(conf.wordSizeBit bits))
  /** output */
  val pgResp   = master Stream (PageWriterResp(conf))
  /** control signals */
  val initEn   = in Bool()
  val clearInitStatus = in Bool()
  val initDone = out Bool()

  /** hashTab memory interface */
  val axiMem   = Vec(master(Axi4(Axi4ConfigAlveo.u55cHBM)), conf.htConf.sizeFSMArray + 1)
  
  // SSD Intf for TB
  val SSDDataIn  = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDDataOut = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDInstrIn = master Stream (SSDInstr(conf))

  /** pgStore throughput control factor */
  val factorThrou = in UInt(5 bits)
}

case class WrapDedupCore() extends Component {

  val dedupConf = DedupConfig()
  val io = WrapDedupCoreIO(dedupConf)

  /** fragmentize pgStream */
  // val dataTransContinueCond = dedupCoreIFFSM.isActive(dedupCoreIFFSM.WAIT_FOR_DATA) | dedupCoreIFFSM.isActive(dedupCoreIFFSM.BUSY)
  // val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn.continueWhen(dataTransContinueCond), dedupConf.pgWord)
  val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn, dedupConf.pgWord)
  /** stream fork */
  val (pgStrmHashTableSS, pgStrmPageWriterSS) = StreamFork2(pgStrmFrgm)
  val (opStrmHashTableSS, opStrmPageWriterSS) = StreamFork2(io.opStrmIn)

  /** modules */
  val hashTableSS = new HashTableSubSystem(dedupConf)
  // val pgWriter = new PageWriter(PageWriterConfig(), dedupConf.instrPgCountWidth)
  val pgWriterSS = new PageWriterSubSystem(dedupConf)

  // data and instr
  hashTableSS.io.opStrmIn     << opStrmHashTableSS
  hashTableSS.io.pgStrmFrgmIn << pgStrmHashTableSS

  pgWriterSS.io.opStrmIn      << opStrmPageWriterSS
  pgWriterSS.io.pgStrmFrgmIn  << pgStrmPageWriterSS

  // hash table: res and axi
  hashTableSS.io.res    >> pgWriterSS.io.lookupRes
  // io.axiMem             := hashTableSS.io.axiMem
  for (idx <- 0 until dedupConf.htConf.sizeFSMArray + 1){
    hashTableSS.io.axiMem(idx) >> io.axiMem(idx)
  }

  //page writer: res to host and to SSD(only in tb)
  pgWriterSS.io.SSDDataIn   >> io.SSDDataIn 
  pgWriterSS.io.SSDDataOut  << io.SSDDataOut
  pgWriterSS.io.SSDInstrIn  >> io.SSDInstrIn
  pgWriterSS.io.res         >> io.pgResp
  pgWriterSS.io.factorThrou := io.factorThrou

  /** init signals */
  hashTableSS.io.initEn := io.initEn
  hashTableSS.io.clearInitStatus := io.clearInitStatus
  pgWriterSS.io.initEn  := io.initEn
  io.initDone           := hashTableSS.io.initDone

}
