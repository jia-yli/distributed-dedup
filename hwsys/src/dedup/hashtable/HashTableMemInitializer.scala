package dedup
package hashtable

import util._

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._

case class HashTableMemInitializerIO(htConf: HashTableConfig) extends Bundle {
  val initEn      = in Bool()
  val clearInitStatus = in Bool()
  val initDone    = out Bool()
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = master(Axi4(axiConf))
}

case class HashTableMemInitializer (htConf: HashTableConfig) extends Component {

  val io = HashTableMemInitializerIO(htConf)

  /* config*/
  val burstLen:BigInt = if (htConf.bucketMetaDataType.getBitsWidth == 128){
      if((htConf.nBucket/8) < 32) (htConf.nBucket/8) else 32 // number of 512b data transfer per request, for 128b metadata
    } else{
      if((htConf.nBucket/2) < 32) (htConf.nBucket/2) else 32 // number of 512b data transfer per request, for 512b metadata
    }
  
  val reqByteSize = burstLen * io.axiConf.dataWidth / 8
  val totalReqCnt =  (htConf.bucketMetaDataByteSize * htConf.nBucket) / reqByteSize
  val reqAddrBitShift = log2Up(reqByteSize)

  /** default status of strems */
  io.axiMem.setIdle()

  /** Resource */
  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val maxOutStandingReqest = 16

  val cntMemInitAXIAddr = Counter(totalReqCnt, io.axiMem.aw.fire)
  val cntDataFrgm       = Counter(burstLen, io.axiMem.w.fire)
  val cntMemInitAXIData = Counter(totalReqCnt, io.axiMem.w.fire & io.axiMem.w.last)
  val cntMemInitAXIResp = Counter(totalReqCnt, io.axiMem.b.fire)

  val memInitAXIAddrAllIssued = Reg(Bool())
  val memInitAXIDataAllIssued = Reg(Bool())

  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val INIT = new State

    always{
      when(io.initEn){
        cntMemInitAXIAddr.clear()
        cntDataFrgm.clear()
        cntMemInitAXIData.clear()
        cntMemInitAXIResp.clear()
        memInitAXIAddrAllIssued := False
        memInitAXIDataAllIssued := False
        when(io.clearInitStatus){
          rInitDone := False
        }
        goto(INIT)
      }
    }

    IDLE.whenIsActive{
      io.axiMem.setIdle()
    }

    INIT.whenIsActive{

      io.axiMem.b.ready := True
      when(cntMemInitAXIResp.willOverflow){
        // done
        rInitDone := True
        goto(IDLE)
      }

      val issueAddrContinue = Bool()
      val issueDataContinue = Bool()
      if (totalReqCnt <= maxOutStandingReqest){
        issueAddrContinue := True
        issueDataContinue := True
      } else {
        issueAddrContinue := (cntMemInitAXIAddr - cntMemInitAXIResp) <= maxOutStandingReqest
        issueDataContinue := (cntMemInitAXIData - cntMemInitAXIResp) <= maxOutStandingReqest
      }

      when(!(memInitAXIAddrAllIssued & memInitAXIDataAllIssued)){     
        val metaDataStartAddr = htConf.hashTableOffset + (cntMemInitAXIAddr << reqAddrBitShift)
        io.axiMem.aw.addr  := metaDataStartAddr.resized
        io.axiMem.aw.id    := 0
        io.axiMem.aw.len   := burstLen - 1
        io.axiMem.aw.size  := log2Up(io.axiConf.dataWidth/8)
        io.axiMem.aw.setBurstINCR()
        io.axiMem.aw.valid := (!memInitAXIAddrAllIssued) & issueAddrContinue
        
        when(cntMemInitAXIAddr.willOverflow){
          memInitAXIAddrAllIssued := True
        }

        io.axiMem.w.data  := 0
        io.axiMem.w.last  := cntDataFrgm.willOverflowIfInc
        io.axiMem.w.strb  := (BigInt(1)<<(io.axiConf.dataWidth/8))-1
        io.axiMem.w.valid := (!memInitAXIDataAllIssued) & issueDataContinue

        when(cntMemInitAXIData.willOverflow){
          memInitAXIDataAllIssued := True
        }
      }
    }
  }
}