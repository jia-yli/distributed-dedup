package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.crypto.hash.sha3._

import util.Stream2StreamFragment
import scala.util.Random

import dedup.fingerprint.FingerprintEngineConfig
import dedup.fingerprint.SHA3Config
import dedup.hashtable.HashTableConfig
import dedup.registerfile.RegisterFileConfig
import dedup.routingtable.RoutingTableConfig
import dedup.pagewriter.PageWriterConfig

import dedup.hashtable.HashTableSubSystem
import dedup.pagewriter.PageWriterResp
import dedup.pagewriter.SSDInstr
import dedup.pagewriter.PageWriterSubSystem
import dedup.fingerprint.FingerprintEngine
import dedup.registerfile.RegisterFile
import dedup.routingtable.RoutingTableTop

case class DedupConfig(
  /* general config */
  pgSize   : Int = 4 * 1024, // 4kiB
  wordSize : Int = 64,       // 64B

  /*instr config*/
  instrTotalWidth    : Int = 512,
  LBAWidth           : Int = 32,
  // hashInfoTotalWidth : Int = 32 * 3 + 256,

  // /* instr queue config */
  // instrQueueLogDepth : List[Int] = List(2,6,6), // depth = 4,64,64

  /* multi-board */
  nodeIdxWidth : Int = 4,    // up to 16 nodes
  networkWordSize : Int = 64, // 64B per word

  /** config of submodules */
  // fingerprint engine
  feConf : FingerprintEngineConfig = FingerprintEngineConfig (readyQueueLogDepth = 7, 
                                                              waitingQueueLogDepth = 7,
                                                              sha3ResQueueLogDepth = 7),
  // SHA3 
  sha3Conf : SHA3Config = SHA3Config(dataWidth = 512, sha3Type = SHA3_256, groupSize = 64),

  // register file config
  rfConf : RegisterFileConfig = RegisterFileConfig(tagWidth = 8), // 256 regs in register file

  // routing config
  rtConf : RoutingTableConfig = RoutingTableConfig(routingChannelCount = 8, 
                                                   routingDecisionBits = 16),
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

  pwConf : PageWriterConfig = PageWriterConfig(readyQueueLogDepth = 2,
                                               waitingQueueLogDepth = 8,
                                               // old impl: 8192 x 2
                                               // 128 x 64 fragment = 8192 = 1 << 13 ~ SHA3 latency x2
                                               // + network latency + margin: (1 << 13) x 4 = 1 << 15
                                               pageFrgmQueueLogDepth = 15,
                                               pageFrgmQueueCount  = 4)){
  val wordSizeBit = wordSize * 8 // 512 bit
  val pgWord = pgSize / wordSize // 64 word per page
  val networkWordSizeBit = networkWordSize * 8 // 512 bit
  assert(pgSize % wordSize == 0)
}

case class WrapDedupCoreIO(conf: DedupConfig) extends Bundle {
  /** input */
  val opStrmIn = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmIn = slave Stream (Bits(conf.wordSizeBit bits))
  /** output */
  val pgResp   = master Stream (PageWriterResp(conf))
  /** inter-board via network */
  val remoteRecvStrmIn  = slave Stream(Bits(conf.networkWordSizeBit bits))
  val remoteSendStrmOut = Vec(master Stream(Bits(conf.networkWordSizeBit bits)), conf.rtConf.routingChannelCount - 1)
  /** control signals */
  val initEn   = in Bool()
  val clearInitStatus = in Bool()
  val initDone = out Bool()

  // routing table config port
  val updateRoutingTableContent = in Bool()
  val routingTableContent = new Bundle{
    val hashValueStart = in Vec(UInt(conf.rtConf.routingDecisionBits bits), conf.rtConf.routingChannelCount)
    val hashValueLen = in Vec(UInt((conf.rtConf.routingDecisionBits + 1) bits), conf.rtConf.routingChannelCount)
    val nodeIdx = in Vec(UInt(conf.nodeIdxWidth bits), conf.rtConf.routingChannelCount)
    val activeChannelCount = in UInt((conf.rtConf.routingChannelLogCount + 1) bits)
  }

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
  val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn, dedupConf.pgWord)
  /** stream fork */
  val (opStrmFingerprint, opStrmPageWriterSS) = StreamFork2(io.opStrmIn)
  val (pgStrmFingerprint, pgStrmPageWriterSS) = StreamFork2(pgStrmFrgm)

  /** modules */
  val fingerprintEngine = FingerprintEngine(dedupConf)
  val registerFile = RegisterFile(dedupConf)
  val routingTable = RoutingTableTop(dedupConf)
  val hashTableSS = HashTableSubSystem(dedupConf)
  val pgWriterSS = PageWriterSubSystem(dedupConf)

  // fringerprint engine
  fingerprintEngine.io.initEn       := io.initEn
  fingerprintEngine.io.opStrmIn     << opStrmFingerprint
  fingerprintEngine.io.pgStrmFrgmIn << pgStrmFingerprint
  fingerprintEngine.io.res          >> registerFile.io.unregisteredInstrIn

  // register file
  registerFile.io.registeredInstrOut >> routingTable.io.localInstrIn
  registerFile.io.lookupResOut       >> pgWriterSS.io.lookupRes

  // routing table
  routingTable.io.remoteRecvStrmIn     << io.remoteRecvStrmIn
  routingTable.io.localInstrOut        >> hashTableSS.io.opStrmIn
  routingTable.io.localWriteBackResOut >> registerFile.io.lookupResWriteBackIn
  (routingTable.io.remoteSendStrmOut, io.remoteSendStrmOut).zipped.foreach{_ >> _}
  routingTable.io.updateRoutingTableContent := io.updateRoutingTableContent
  routingTable.io.routingTableContent assignAllByName io.routingTableContent

  // hash table
  hashTableSS.io.initEn          := io.initEn
  hashTableSS.io.clearInitStatus := io.clearInitStatus
  hashTableSS.io.updateRoutingTableContent := io.updateRoutingTableContent
  hashTableSS.io.nodeIdx         := io.routingTableContent.nodeIdx(0) // hard-coded: 0th entry must be local node
  io.initDone                    := hashTableSS.io.initDone
  hashTableSS.io.res             >> routingTable.io.localWriteBackResIn
  for (idx <- 0 until dedupConf.htConf.sizeFSMArray + 1){
    hashTableSS.io.axiMem(idx) >> io.axiMem(idx)
  }

  // page writer
  pgWriterSS.io.initEn        := io.initEn
  pgWriterSS.io.opStrmIn      << opStrmPageWriterSS
  pgWriterSS.io.pgStrmFrgmIn  << pgStrmPageWriterSS
  pgWriterSS.io.res         >> io.pgResp
  pgWriterSS.io.SSDDataIn   >> io.SSDDataIn 
  pgWriterSS.io.SSDDataOut  << io.SSDDataOut
  pgWriterSS.io.SSDInstrIn  >> io.SSDInstrIn
  pgWriterSS.io.factorThrou := io.factorThrou
}
