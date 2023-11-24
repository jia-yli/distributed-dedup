package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import routingtable.RoutedLookupInstr
import routingtable.RoutedWriteBackLookupRes

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
  val updateRoutingTableContent = in Bool()
  val nodeIdx      = in UInt(conf.nodeIdxWidth bits)
  val initDone     = out Bool ()
  val opStrmIn     = slave Stream (RoutedLookupInstr(conf))
  val res          = master Stream (RoutedWriteBackLookupRes(conf))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = Vec(master(Axi4(axiConf)), conf.htConf.sizeFSMArray + 1)
}

case class HashTableSubSystem(conf : DedupConfig) extends Component {
  val io     = HashTableSSIO(conf)

  val htConf = conf.htConf

  val lookupEngine = HashTableLookupEngine(conf)

  val memAllocator = MemManager(htConf)
  // memAllocator.io.initEn := io.initEn

  io.initDone := lookupEngine.io.initDone

  io.opStrmIn >> lookupEngine.io.instrStrmIn

  lookupEngine.io.initEn             := io.initEn
  lookupEngine.io.clearInitStatus    := io.clearInitStatus
  lookupEngine.io.res                >> io.res
  lookupEngine.io.updateRoutingTableContent := io.updateRoutingTableContent
  lookupEngine.io.nodeIdx            := io.nodeIdx
  // io.axiMem                          := lookupEngine.io.axiMem
  for (idx <- 0 until htConf.sizeFSMArray){
    lookupEngine.io.axiMem(idx) >> io.axiMem(idx)
  }
  memAllocator.io.axiMem >> io.axiMem(htConf.sizeFSMArray)

  lookupEngine.io.mallocIdx          << memAllocator.io.mallocIdx
  lookupEngine.io.freeIdx            >> memAllocator.io.freeIdx
}

