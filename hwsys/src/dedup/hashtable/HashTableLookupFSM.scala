package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import routingtable.RoutedLookupInstr
import routingtable.RoutedWriteBackLookupRes
import util.StreamFork4

case class HashTableBucketMetaData (htConf: HashTableConfig) extends Bundle {
  // Block Addr = entry idx
  val head    = UInt(htConf.ptrWidth bits)
  val bloomFilter = (htConf.bfEnable) generate (Bits(htConf.m bits))
  val paddingWidth = if (htConf.bfEnable) (512 - htConf.ptrWidth - htConf.m) else (512 - htConf.ptrWidth)
  val padding = UInt(paddingWidth bits)
  // val tail    = UInt(htConf.ptrWidth bits)
  // // lenth of the linked list
  // val len     = UInt(htConf.ptrWidth bits)
  // val padding = UInt((512 - 3 * htConf.ptrWidth) bits)
}

case class HashTableBucketMetaDataNoPadding (htConf: HashTableConfig) extends Bundle {
  // Block Addr = entry idx
  val head    = UInt(htConf.ptrWidth bits)
  val bloomFilter = (htConf.bfEnable) generate (Bits(htConf.m bits))
  // val tail    = UInt(htConf.ptrWidth bits)
  // // lenth of the linked list
  // val len     = UInt(htConf.ptrWidth bits)
}

case class HashTableEntry (htConf: HashTableConfig) extends Bundle {
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val RefCount = UInt(htConf.ptrWidth bits)
  val SSDLBA   = UInt(htConf.ptrWidth bits)
  val next     = UInt(htConf.ptrWidth bits)
  val padding  = UInt((htConf.hashValWidth - 3 * htConf.ptrWidth) bits)
}

case class HashTableEntryNoPadding (htConf: HashTableConfig) extends Bundle {
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val RefCount = UInt(htConf.ptrWidth bits)
  val SSDLBA   = UInt(htConf.ptrWidth bits)
  val next     = UInt(htConf.ptrWidth bits)
}

// size = #entry inside
//hashTableOffset = global memory offset of the hash table
// index -> entry0 -> entry1 -> ......
case class HashTableLookupFSMIO(conf: DedupConfig) extends Bundle {
  val htConf      = conf.htConf
  val initEn      = in Bool()
  val nodeIdx     = in UInt(conf.nodeIdxWidth bits)
  // execution results
  val instrStrmIn = slave Stream(RoutedLookupInstr(conf))
  val res         = master Stream(RoutedWriteBackLookupRes(conf))
  // interface the lock manager
  val lockReq     = master Stream(FSMLockRequest(htConf))
  // interface to allocator
  val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
  val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = master(Axi4(axiConf))
}

case class HashTableLookupFSM (conf: DedupConfig, FSMId: Int = 0) extends Component {
  val htConf   = conf.htConf
  val rFSMId   = Reg(UInt(log2Up(htConf.sizeFSMArray) bits)) init U(FSMId) allowUnsetRegToAvoidLatch
  val rNodeIdx = Reg(UInt(conf.nodeIdxWidth bits)) init 0
  when(io.initEn){
    rNodeIdx := io.nodeIdx
  }

  val io = HashTableLookupFSMIO(conf)

  /** default status of strems */
  io.instrStrmIn.setBlocked()
  io.res.setIdle()
  io.lockReq.setIdle()
  io.mallocIdx.setBlocked()
  io.freeIdx.setIdle()
  io.axiMem.writeCmd.setIdle()
  io.axiMem.writeData.setIdle()
  io.axiMem.writeRsp.setBlocked()
  io.axiMem.readCmd.setIdle()

  val instr      = Reg(RoutedLookupInstr(conf))
  val idxBucket  = instr.SHA3Hash(htConf.idxBucketWidth-1 downto 0).asUInt // lsb as the bucket index
  val bfLookupIdx = (htConf.bfEnable) generate (Vec(UInt(htConf.mWidth bits), htConf.k))
  val bfLookupMask = (htConf.bfEnable) generate (Vec(Bits(htConf.m bits), htConf.k))
  if (htConf.bfEnable){
    bfLookupIdx.assignFromBits(instr.SHA3Hash(htConf.hashValWidth -1 downto htConf.hashValWidth - (htConf.mWidth * htConf.k)))
    for (idx <- 0 until htConf.k){
      bfLookupMask(idx) := (U(1) << bfLookupIdx(idx)).asBits
    }
  }
  val bfLookupMaskReduced = (htConf.bfEnable) generate (bfLookupMask.reduceBalancedTree(_ | _))
  val rBFLookupMaskReduced = (htConf.bfEnable) generate (Reg(Bits(htConf.m bits)) init 0)

  /* two additional pipeline stages for readRsp, otherwise timing not meet in PnR */
  val readRspStage1 = io.axiMem.readRsp // already pipelined in dedupSys
  val readRspStage2 = readRspStage1.pipelined(StreamPipe.FULL)
  readRspStage2.setBlocked()

  val hashComparator = new Area{
    // stage 1
    // for hash comparison
    val decodedHashTableEntry = HashTableEntryNoPadding(htConf)
    decodedHashTableEntry.assignFromBits(readRspStage1.data, decodedHashTableEntry.getBitsWidth - 1, 0)
    val readHashSliced   = decodedHashTableEntry.SHA3Hash.subdivideIn(8 slices)
    val instrHashSliced  = instr.SHA3Hash.subdivideIn(8 slices)
    // for BF reconstruction
    val bfIdx = (htConf.bfEnable) generate (Vec(UInt(htConf.mWidth bits), htConf.k))
    val bfMask = (htConf.bfEnable) generate (Vec(Bits(htConf.m bits), htConf.k))
    if (htConf.bfEnable){
      bfIdx.assignFromBits(decodedHashTableEntry.SHA3Hash(htConf.hashValWidth -1 downto htConf.hashValWidth - (htConf.mWidth * htConf.k)))
      for (idx <- 0 until htConf.k){
        bfMask(idx) := (U(1) << bfIdx(idx)).asBits
      }
    }
    // stage 2
    val equalCheckStage1 = RegNext((readHashSliced, instrHashSliced).zipped.map(_ === _).asBits())
    val bfMaskReduced    = (htConf.bfEnable) generate (RegNext(bfMask.reduceBalancedTree(_ | _)))
    val isHashValMatch   = equalCheckStage1.andR
  }

  val fsm = new StateMachine {
    val FETCH_INSTRUCTION                                                         = new State with EntryPoint
    val LOCK_ACQUIRE, FETCH_METADATA, FETCH_ENTRY, EXEC, WRITE_BACK, LOCK_RELEASE = new State

    always{
      when(io.initEn){
        goto(FETCH_INSTRUCTION)
      }
    }

    FETCH_INSTRUCTION.whenIsActive {
      io.instrStrmIn.ready := True
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()
      // instruction fetching
      when(io.instrStrmIn.fire){
        instr := io.instrStrmIn.payload
        goto(LOCK_ACQUIRE)
      }
    }

    LOCK_ACQUIRE.whenIsActive {
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()

      io.lockReq.valid             := True
      io.lockReq.payload.idxBucket := idxBucket
      io.lockReq.payload.FSMId     := rFSMId.resized
      io.lockReq.payload.opCode    := LockManagerOp.ACQUIRE
      // acquire lock from lock manager
      when(io.lockReq.fire){
        goto(FETCH_METADATA)
      }
    }

    val fetchMetaDataAXIIssued   = Reg(Bool())
    val fetchMetaDataAXIReceived = Reg(Bool())
    val bucketMetaData           = Reg(HashTableBucketMetaDataNoPadding(htConf))
    val bloomFilterLookupRes     = (htConf.bfEnable) generate Reg(Bool())
    val reconstructedBloomFilter = (htConf.bfEnable) generate Reg(Bits(htConf.m bits))
    val nextEntryIdx             = Reg(UInt(htConf.ptrWidth bits))

    FETCH_METADATA.onEntry{
      fetchMetaDataAXIIssued   := False
      fetchMetaDataAXIReceived := False
      bucketMetaData.head      := 0
      if (htConf.bfEnable){
        bucketMetaData.bloomFilter := 0
        bloomFilterLookupRes     := True
        reconstructedBloomFilter := 0
      }
      // bucketMetaData.tail      := 0
      // bucketMetaData.len       := 0
      nextEntryIdx             := 0
      if (htConf.bfEnable){
        rBFLookupMaskReduced     := bfLookupMaskReduced
      }
    }

    FETCH_METADATA.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()
      
      when(!fetchMetaDataAXIIssued){
        val metaDataStartAddr    = htConf.hashTableOffset + (idxBucket << htConf.bucketMetaDataAddrBitShift)
        val burstLen             = 1
        io.axiMem.readCmd.addr  := metaDataStartAddr.resized
        io.axiMem.readCmd.id    := rFSMId.resized
        io.axiMem.readCmd.len   := burstLen-1
        io.axiMem.readCmd.size  := htConf.bucketMetaDataAddrBitShift
        io.axiMem.readCmd.setBurstINCR()
        io.axiMem.readCmd.valid := True

        when(io.axiMem.readCmd.fire){
          fetchMetaDataAXIIssued := True
        }
      }.elsewhen(!fetchMetaDataAXIReceived){
        // command issued, wait for response
        readRspStage2.ready  := True
        when(readRspStage2.fire){
          fetchMetaDataAXIReceived := True
          val decodedMetaData = HashTableBucketMetaDataNoPadding(htConf)
          decodedMetaData.assignFromBits(readRspStage2.data, bucketMetaData.getBitsWidth - 1, 0)
          bucketMetaData := decodedMetaData
          if (htConf.bfEnable){
            bloomFilterLookupRes := ((decodedMetaData.bloomFilter & rBFLookupMaskReduced) === rBFLookupMaskReduced)
          }
          nextEntryIdx   := decodedMetaData.head
          goto(FETCH_ENTRY)
        }
      }
    }

    val cntFetchEntryAXIIssued   = Counter(htConf.ptrWidth bits, io.axiMem.readCmd.fire)
    val cntFetchEntryAXIReceived = Counter(htConf.ptrWidth bits, readRspStage2.fire)

    val lookupIsExist     = Reg(Bool()) // lookup result
    // current fetched entry
    val currentEntry      = Reg(HashTableEntryNoPadding(htConf))
    val currentEntryIdx   = Reg(UInt(htConf.ptrWidth bits))
    val currentEntryValid = Reg(Bool())
    // buffered prev entry
    val prevEntry         = RegNextWhen(currentEntry, readRspStage2.fire)
    val prevEntryIdx      = RegNextWhen(currentEntryIdx, readRspStage2.fire)
    val prevEntryValid    = RegNextWhen(currentEntryValid, readRspStage2.fire)

    FETCH_ENTRY.onEntry{
      cntFetchEntryAXIIssued.clear()
      cntFetchEntryAXIReceived.clear()
      
      lookupIsExist         := False
      
      currentEntry.SHA3Hash := 0
      currentEntry.RefCount := 0
      currentEntry.SSDLBA   := 0
      currentEntry.next     := 0
      currentEntryIdx       := 0
      currentEntryValid     := False
      
      prevEntry.SHA3Hash    := 0
      prevEntry.RefCount    := 0
      prevEntry.SSDLBA      := 0
      prevEntry.next        := 0
      prevEntryIdx          := 0
      prevEntryValid        := False
    }

    FETCH_ENTRY.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()

      // fetch entry
      val bypassCondition = Bool()
      if (htConf.bfEnable) {
        bypassCondition := ((bucketMetaData.head === 0) || (!bloomFilterLookupRes) || (nextEntryIdx === 0))
      } else {
        bypassCondition := ((bucketMetaData.head === 0) || (nextEntryIdx === 0))
      }
      // when((bucketMetaData.head === 0 ) | (bucketMetaData.tail === 0 ) | (bucketMetaData.len === 0 )){
      // when((bucketMetaData.head === 0)){
      //   // empty bucket, no lookup anymore
      //   goto(EXEC)
      // }.elsewhen(!bloomFilterLookupRes){
      //   // not in bloom filter, bypass
      //   goto(EXEC)
      // }.elsewhen(nextEntryIdx === 0){
      //   // we are at the last entry, no lookup anymore
      //   goto(EXEC)
      when(bypassCondition){
        goto(EXEC)
      }.otherwise{
        // bucket not empty, and not the last entry, fetch entry
        val entryStartAddr       = htConf.hashTableContentOffset + (nextEntryIdx << htConf.entryAddrBitShift)
        val burstLen             = 1
        io.axiMem.readCmd.addr  := entryStartAddr.resized
        io.axiMem.readCmd.id    := rFSMId.resized
        io.axiMem.readCmd.len   := burstLen-1
        io.axiMem.readCmd.size  := htConf.entryAddrBitShift
        io.axiMem.readCmd.setBurstINCR()
        io.axiMem.readCmd.valid := (cntFetchEntryAXIIssued === cntFetchEntryAXIReceived) & (!lookupIsExist)

        readRspStage2.ready := True
        when(readRspStage2.fire){
          val decodedHashTableEntry = HashTableEntryNoPadding(htConf)
          decodedHashTableEntry.assignFromBits(readRspStage2.data, decodedHashTableEntry.getBitsWidth - 1, 0)
          currentEntry      := decodedHashTableEntry
          currentEntryIdx   := nextEntryIdx
          currentEntryValid := True
          nextEntryIdx      := decodedHashTableEntry.next
          if (htConf.bfEnable) {
            reconstructedBloomFilter := (reconstructedBloomFilter | hashComparator.bfMaskReduced)
          }

          when(hashComparator.isHashValMatch){
            // find
            lookupIsExist := True
            goto(EXEC)
          }
        }
      }
    }

    val mallocDone = Reg(Bool())
    val freeDone   = Reg(Bool())

    // bucket status
    // val resIsEmptyBucket  = (!prevEntryValid) & (!currentEntryValid)
    // val resIsFirstElement = (!prevEntryValid) & (currentEntryValid)
    // val resIsNormal       = (prevEntryValid) & (currentEntryValid)

    // control write back True => need to write back
    val needMetaDataWriteBack     = Reg(Bool())
    val needCurrentEntryWriteBack = Reg(Bool())
    val needPrevEntryWriteBack    = Reg(Bool())

    EXEC.onEntry{
      mallocDone                    := False
      freeDone                      := False
      // is need write back(updated)
      needMetaDataWriteBack         := False
      needCurrentEntryWriteBack     := False
      needPrevEntryWriteBack        := False
    }

    EXEC.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()

      when(instr.opCode === DedupCoreOp.WRITE2FREE){
        // insert
        when(lookupIsExist){
          // exist, update ref counter
          currentEntry.RefCount := currentEntry.RefCount + 1
          // only need to write back current entry
          needMetaDataWriteBack      := False
          needCurrentEntryWriteBack  := True
          needPrevEntryWriteBack     := False

          // check if optimized BF reconstruct is enabled
          if (htConf.bfEnable && htConf.bfOptimizedReconstruct) {
            when(currentEntry.next === 0){
              // lookup exist(not FP), but we reach the last, we can reconstruct
              bucketMetaData.bloomFilter := reconstructedBloomFilter
              needMetaDataWriteBack      := True
            }
          }          
          goto(WRITE_BACK)
        }.otherwise{
          // new, insert
          io.mallocIdx.ready    := !mallocDone
          // update everything
          needMetaDataWriteBack      := True // new header + new BF
          needCurrentEntryWriteBack  := True // new entry
          needPrevEntryWriteBack     := False // insert in posi 0, no prev entry

          /* no 0 in the idx, 0 == invalid
            need fire & (!(io.mallocIdx.payload === U(0)))
            but we move the 0-detection to the outer lookupEngine module
          */
          when(io.mallocIdx.fire){
            mallocDone := True
            val newIdx  = io.mallocIdx.payload
            // update new entry
            currentEntry.SHA3Hash := instr.SHA3Hash
            currentEntry.RefCount := 1
            currentEntry.SSDLBA   := newIdx
            currentEntry.next     := bucketMetaData.head
            currentEntryIdx       := newIdx
            currentEntryValid     := True

            bucketMetaData.head   := newIdx
            if (htConf.bfEnable) {
              when(bloomFilterLookupRes){
                // BF says it's inside, but it's not -> FP and reconstruct BF
                bucketMetaData.bloomFilter := (reconstructedBloomFilter | rBFLookupMaskReduced)
              }.otherwise{
                // BF says it's not inside -> normal insertion
                bucketMetaData.bloomFilter := (bucketMetaData.bloomFilter | rBFLookupMaskReduced)
              }
            }
            // update old entry
            // prevEntry.SHA3Hash    := currentEntry.SHA3Hash
            // prevEntry.RefCount    := currentEntry.RefCount
            // prevEntry.SSDLBA      := currentEntry.SSDLBA
            // prevEntry.next        := newIdx
            // prevEntryIdx          := currentEntryIdx
            // prevEntryValid        := currentEntryValid
            // update metadata: if empty, change head
            // bucketMetaData.head    := resIsEmptyBucket ? newIdx | bucketMetaData.head
            // bucketMetaData.tail    := newIdx
            // bucketMetaData.len     := bucketMetaData.len + 1
            goto(WRITE_BACK)
          }
        }
      }.elsewhen(instr.opCode === DedupCoreOp.ERASEREF){
        // erase
        when(lookupIsExist){
          when(currentEntry.RefCount > 1){
            // only update ref counter
            currentEntry.RefCount := currentEntry.RefCount - 1
            // only update current entry
            needMetaDataWriteBack      := False
            needCurrentEntryWriteBack  := True
            needPrevEntryWriteBack     := False
            goto(WRITE_BACK)
          }.otherwise{
            // GC
            io.freeIdx.valid     := !freeDone
            io.freeIdx.payload   := currentEntryIdx

            // update metadata and prevpage, current page is deleted
            val resIsFirstElement = (!prevEntryValid) & (currentEntryValid)
            needMetaDataWriteBack     := resIsFirstElement
            needCurrentEntryWriteBack := False
            needPrevEntryWriteBack    := True

            when(io.freeIdx.fire){
              freeDone := True
              // free current entry
              currentEntry.SHA3Hash := currentEntry.SHA3Hash
              currentEntry.RefCount := 0
              currentEntry.SSDLBA   := currentEntry.SSDLBA  
              currentEntry.next     := currentEntry.next    
              currentEntryIdx       := currentEntryIdx
              // update old entry (if exist)
              prevEntry.SHA3Hash    := prevEntry.SHA3Hash
              prevEntry.RefCount    := prevEntry.RefCount
              prevEntry.SSDLBA      := prevEntry.SSDLBA
              prevEntry.next        := currentEntry.next
              prevEntryIdx          := prevEntryIdx
              // update metadata: if empty, change head
              bucketMetaData.head    := (resIsFirstElement) ? nextEntryIdx | bucketMetaData.head
              if (htConf.bfEnable) {
                bucketMetaData.bloomFilter := bucketMetaData.bloomFilter
              }
              // bucketMetaData.tail    := (currentEntryIdx === bucketMetaData.tail) ? (prevEntryValid ? prevEntryIdx | U(0)) | bucketMetaData.tail
              // bucketMetaData.len     := (bucketMetaData.len > 0) ? (bucketMetaData.len - 1) | U(0)

              goto(WRITE_BACK)
            }
          }
        }.otherwise{
          // TODO: add raise exception
          goto(WRITE_BACK)
        }
      }.elsewhen(instr.opCode === DedupCoreOp.READSSD){
        // read
        when(lookupIsExist){
          // no update on ref counter
          // no update on entries
          needMetaDataWriteBack      := False
          needCurrentEntryWriteBack  := False
          needPrevEntryWriteBack     := False
          goto(WRITE_BACK)
        }.otherwise{
          // TODO: add raise exception
          goto(WRITE_BACK)
        }
      }
    }

    val doneMetaDataWriteBackAddr     = Reg(Bool())
    val doneCurrentEntryWriteBackAddr = Reg(Bool())
    val donePrevEntryWriteBackAddr    = Reg(Bool())

    val doneMetaDataWriteBackData     = Reg(Bool())
    val doneCurrentEntryWriteBackData = Reg(Bool())
    val donePrevEntryWriteBackData    = Reg(Bool())

    // doneMetaDataWriteBackAddr     := !needMetaDataWriteBack   
    // doneCurrentEntryWriteBackAddr := !(needCurrentEntryWriteBack & currentEntryValid)
    // donePrevEntryWriteBackAddr    := !(needPrevEntryWriteBack & prevEntryValid)

    // doneMetaDataWriteBackData     := !needMetaDataWriteBack   
    // doneCurrentEntryWriteBackData := !(needCurrentEntryWriteBack & currentEntryValid)
    // donePrevEntryWriteBackData    := !(needPrevEntryWriteBack & prevEntryValid)

    val cntWriteResponse = Counter(4, io.axiMem.writeRsp.fire)

    WRITE_BACK.onEntry{
      doneMetaDataWriteBackAddr     := False
      doneCurrentEntryWriteBackAddr := False
      donePrevEntryWriteBackAddr    := False

      doneMetaDataWriteBackData     := False
      doneCurrentEntryWriteBackData := False
      donePrevEntryWriteBackData    := False

      cntWriteResponse.clear() 
    }

    WRITE_BACK.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()

      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()

      io.axiMem.writeRsp.ready := True
      val responseNeeded = Vec(needMetaDataWriteBack, needCurrentEntryWriteBack & currentEntryValid, needPrevEntryWriteBack & prevEntryValid).sCount(True)
      when(cntWriteResponse === responseNeeded){
        goto(LOCK_RELEASE)
      }

      when(needMetaDataWriteBack & !(doneMetaDataWriteBackAddr & doneMetaDataWriteBackData)){
        // write back metadata
        val metaDataStartAddr     = htConf.hashTableOffset + (idxBucket << htConf.bucketMetaDataAddrBitShift)
        io.axiMem.writeCmd.addr  := metaDataStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.bucketMetaDataAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needMetaDataWriteBack & (!doneMetaDataWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          doneMetaDataWriteBackAddr := True
        }

        io.axiMem.writeData.data  := (bucketMetaData.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.bucketMetaDataByteSize)-1
        io.axiMem.writeData.valid := needMetaDataWriteBack & (!doneMetaDataWriteBackData)

        when(io.axiMem.writeData.fire){
          doneMetaDataWriteBackData := True
        }
      }.elsewhen(needCurrentEntryWriteBack & currentEntryValid & !(doneCurrentEntryWriteBackAddr & doneCurrentEntryWriteBackData)){
        // write back current page
        val currentEntryStartAddr = htConf.hashTableContentOffset + (currentEntryIdx << htConf.entryAddrBitShift)
        io.axiMem.writeCmd.addr  := currentEntryStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.entryAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needCurrentEntryWriteBack & currentEntryValid & (!doneCurrentEntryWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          doneCurrentEntryWriteBackAddr := True
        }

        io.axiMem.writeData.data  := (currentEntry.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.entryByteSize)-1
        io.axiMem.writeData.valid := needCurrentEntryWriteBack & currentEntryValid & (!doneCurrentEntryWriteBackData)

        when(io.axiMem.writeData.fire){
          doneCurrentEntryWriteBackData := True
        }
      }.elsewhen(needPrevEntryWriteBack & prevEntryValid & !(donePrevEntryWriteBackAddr & donePrevEntryWriteBackData)){
        // write back prev page
        val prevEntryStartAddr    = htConf.hashTableContentOffset + (prevEntryIdx << htConf.entryAddrBitShift)
        io.axiMem.writeCmd.addr  := prevEntryStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.entryAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needPrevEntryWriteBack & prevEntryValid & (!donePrevEntryWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          donePrevEntryWriteBackAddr := True
        }


        io.axiMem.writeData.data  := (prevEntry.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.entryByteSize)-1
        io.axiMem.writeData.valid := needPrevEntryWriteBack & prevEntryValid & (!donePrevEntryWriteBackData)

        when(io.axiMem.writeData.fire){
          donePrevEntryWriteBackData := True
        }
      }
    }

    val lockReleased = Reg(Bool())
    val resFired     = Reg(Bool())

    LOCK_RELEASE.onEntry{
      lockReleased := False   
      resFired     := False
    }

    LOCK_RELEASE.whenIsActive{
      // release lock
      io.instrStrmIn.setBlocked()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.writeRsp.setBlocked()
      io.axiMem.readCmd.setIdle()
      readRspStage2.setBlocked()

      io.lockReq.valid             := !lockReleased
      io.lockReq.payload.idxBucket := idxBucket
      io.lockReq.payload.FSMId     := rFSMId.resized
      io.lockReq.payload.opCode    := LockManagerOp.RELEASE
      // acquire lock from lock manager
      when(io.lockReq.fire){
        lockReleased := True
      }

      io.res.valid            := !resFired
      // io.res.payload.SHA3Hash := currentEntry.SHA3Hash
      // io.res.payload.opCode   := instr.opCode
      io.res.payload.RefCount := currentEntry.RefCount
      io.res.payload.SSDLBA   := currentEntry.SSDLBA
      io.res.payload.nodeIdx  := rNodeIdx
      io.res.payload.tag      := instr.tag
      io.res.payload.dstNode  := instr.srcNode
      // acquire lock from lock manager
      when(io.res.fire){
        resFired := True
      }

      when(lockReleased & resFired){
        goto(FETCH_INSTRUCTION)
      }
    }
  }
}