package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class HashTableConfig (hashValWidth: Int = 256, 
                            ptrWidth: Int = 32, 
                            hashTableSize: BigInt = ((BigInt(1) << 27) + (BigInt(1) << 26)), 
                            expBucketSize: Int = 32, 
                            hashTableOffset: BigInt = (BigInt(1) << 30), 
                            bfEnable: Boolean = true,
                            bfOptimizedReconstruct: Boolean = false,
                            sizeFSMArray: Int = 8) {
  // Instr Decoder
  val readyQueueLogDepth = 7
  val waitingQueueLogDepth = 7
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)

  assert(hashTableSize%expBucketSize==0, "Hash table size (#entry) should be a multiple of bucketSize")
  // #hash index
  val idxBucketWidth = log2Up(hashTableSize / expBucketSize)
  val nBucket: BigInt = 1 << idxBucketWidth
  // property of bucket metadata
  val bucketMetaDataType = Bits(512 bits)
  val bucketMetaDataByteSize = bucketMetaDataType.getBitsWidth/8
  val bucketMetaDataAddrBitShift = log2Up(bucketMetaDataByteSize)

  // property of one entry
  val entryType = Bits(512 bits)
  val entryByteSize = entryType.getBitsWidth/8
  val entryAddrBitShift = log2Up(entryByteSize)
  // memory offset (start of hash table content), minus 1 entry since no index 0.
  val hashTableContentOffset = hashTableOffset + bucketMetaDataByteSize * nBucket - 1 * entryByteSize

  // Lookup FSM settings
  /** Hardware parameters (performance related) */
  val cmdQDepth = 4

  // bloom filter config
  val mWidth = 5
  val m = (1 << mWidth)
  val k = 3
}

case class HashTableSSIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool () 
  val clearInitStatus = in Bool()
  val initDone     = out Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val res          = master Stream (HashTableLookupFSMRes(conf.htConf))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = Vec(master(Axi4(axiConf)), conf.htConf.sizeFSMArray + 1)
}

class HashTableSubSystem(conf : DedupConfig) extends Component {
  val io     = HashTableSSIO(conf)

  val htConf = conf.htConf

  val sha3Grp   = new SHA3Group(conf.sha3Conf)

  val SHA3ResQueue = StreamFifo(Bits(htConf.hashValWidth bits), 64)
  
  val instrDecoder = HashTableInstrDecoder(conf)

  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf),  1 << htConf.waitingQueueLogDepth)

  val decodedReadyInstrQueue = StreamFifo(DecodedReadyInstr(conf),  1 << htConf.readyQueueLogDepth)

  val instrIssuer = HashTableInstrIssuer(conf)

  val lookupEngine = HashTableLookupEngine(htConf)

  val memAllocator = MemManager(htConf)
  // memAllocator.io.initEn := io.initEn

  io.initDone := lookupEngine.io.initDone

  // SHA3 Group + SHA3 res Queue
  sha3Grp.io.initEn := io.initEn
  sha3Grp.io.frgmIn << io.pgStrmFrgmIn.pipelined(StreamPipe.FULL)

  SHA3ResQueue.io.push  << sha3Grp.io.res.pipelined(StreamPipe.FULL)
  SHA3ResQueue.io.flush := io.initEn

  // decoder + decoded instr Queue
  io.opStrmIn.pipelined(StreamPipe.FULL) >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream       >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream     >> decodedWaitingInstrQueue.io.push

  decodedReadyInstrQueue.io.flush    := io.initEn
  decodedWaitingInstrQueue.io.flush  := io.initEn

  // instr issuer and lookup Engine
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop.pipelined(StreamPipe.FULL)
  instrIssuer.io.SHA3ResStream       << SHA3ResQueue.io.pop.pipelined(StreamPipe.FULL)

  instrIssuer.io.instrIssueStream.pipelined(StreamPipe.FULL) >> lookupEngine.io.instrStrmIn

  lookupEngine.io.initEn             := io.initEn
  lookupEngine.io.clearInitStatus    := io.clearInitStatus
  lookupEngine.io.res                >> io.res
  // io.axiMem                          := lookupEngine.io.axiMem
  for (idx <- 0 until htConf.sizeFSMArray){
    lookupEngine.io.axiMem(idx) >> io.axiMem(idx)
  }
  memAllocator.io.axiMem >> io.axiMem(htConf.sizeFSMArray)

  lookupEngine.io.mallocIdx          << memAllocator.io.mallocIdx
  lookupEngine.io.freeIdx            >> memAllocator.io.freeIdx
}

