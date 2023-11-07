package dedup
package registerfile

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._

// import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

object RegisterFileSimHelpers {
  val defaultConf = DedupConfig()
  val conf = defaultConf.copy(rfConf = RegisterFileConfig(tagWidth = 6, // 16 entry
                                                          nodeIdxWidth = 4))
  val instrBitWidth = DedupCoreOp().getBitsWidth

  // Helpers
  def randInstrGen(printRes : Boolean = false) : BigInt = {
    val sha3 = Random.nextInt(1<<20)
    val opcode = 1 + Random.nextInt(3)
    val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
    if (printRes){
      println(s"[RAND Instr] opcode = $opcode, sha3=$sha3_trunc")
    }
    var rawInstr = BigInt(0)
    rawInstr = rawInstr + (BigInt(opcode) << 256)
    rawInstr = rawInstr + (sha3 << 0)
    rawInstr
  }

  def writeBackResGen(RefCount : BigInt,
                      SSDLBA   : BigInt,
                      nodeIdx  : BigInt,
                      tag      : BigInt,
                      printRes : Boolean = false) : BigInt = {
    val resBitWidth = WriteBackLookupRes(conf).getBitsWidth
    var rawRes = BigInt(0)
    rawRes = rawRes + (RefCount << 0)
    rawRes = rawRes + (SSDLBA   << conf.htConf.ptrWidth)
    rawRes = rawRes + (nodeIdx  << (2 * conf.htConf.ptrWidth))
    rawRes = rawRes + (tag      << (resBitWidth - conf.rfConf.tagWidth))
    rawRes
  }
}

case class RegisterFileTB() extends Component{
  val conf = RegisterFileSimHelpers.conf
  val io = new Bundle {
    // local instr input
    val unregisteredInstrIn = slave Stream(UnregisteredLookupInstr(conf))
    // output registered instr, go for routing
    val registeredInstrOut = master Stream(RegisteredLookupInstr(conf))
    val lookupResWriteBackIn = slave Stream(WriteBackLookupRes(conf))
    val lookupResOut = master Stream(HashTableLookupRes(conf))
  }

  val registerFile = RegisterFile(conf)
  registerFile.io <> io
}

class RegisterFileTests extends AnyFunSuite {
  test("RegisterFileTest"){
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(RegisterFileTB())
    else SimConfig.withWave.compile(RegisterFileTB())

    compiledRTL.doSim { dut =>
      RegisterFileSim.sim(dut)
    }
  }
}

object RegisterFileSim {
  def sim(dut: RegisterFileTB): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    dut.io.unregisteredInstrIn.valid  #= false
    dut.io.registeredInstrOut.ready   #= false
    dut.io.lookupResWriteBackIn.valid #= false
    dut.io.lookupResOut.ready         #= false
    dut.clockDomain.waitSampling(10)

    /* Verification settings */
    val totalInstrCount = 512
    val execReshuffleBatchSize = 16
    assert(totalInstrCount%execReshuffleBatchSize == 0)
    assert(execReshuffleBatchSize <= (1 << RegisterFileSimHelpers.conf.rfConf.tagWidth))
    val nodeCount = 8

    /* Stimuli preparation */
    val unregisteredInstrList : ListBuffer[BigInt] = ListBuffer()
    val finalResList          : ListBuffer[BigInt] = ListBuffer()

    /* golen res */
    val goldenSHA3List           : ListBuffer[BigInt] = ListBuffer()
    val goldenRefCountList       : ListBuffer[BigInt] = ListBuffer()
    val goldenSSDLBAList         : ListBuffer[BigInt] = ListBuffer()
    val goldenNodeIdxList        : ListBuffer[BigInt] = ListBuffer()
    val goldenOpCodeList         : ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until totalInstrCount) {
      val randInstr = RegisterFileSimHelpers.randInstrGen()
      unregisteredInstrList.append(randInstr)
      goldenSHA3List.append(SimHelpers.bigIntTruncVal(randInstr, 255, 0))
      goldenOpCodeList.append(SimHelpers.bigIntTruncVal(randInstr, 257, 256))
    }

    /* Stimuli injection */
    val instrPush = fork {
      for (idx <- 0 until totalInstrCount) {
        dut.io.unregisteredInstrIn.sendData(dut.clockDomain, unregisteredInstrList(idx))
      }
      println("[DEBUG]: Done instrPush")
    }

    /* pseudo OoO execution*/
    val execAndWriteBack = fork {
      val receivedInstrCount = 0
      for (batchIdx <- 0 until (totalInstrCount/execReshuffleBatchSize)) {
        println(s"[DEBUG]: Starting execAndWriteBack ${batchIdx} out of ${(totalInstrCount/execReshuffleBatchSize)}")
        // get batch instr
        val recvInstrWaitCycles = Random.nextInt(2)*4
        val batchTagList: ListBuffer[BigInt] = ListBuffer()
        for (instrIdx <- 0 until execReshuffleBatchSize){
          dut.clockDomain.waitSampling(recvInstrWaitCycles)
          val newInstr = dut.io.registeredInstrOut.recvData(dut.clockDomain)
          batchTagList.append(SimHelpers.bigIntTruncVal(newInstr, 
                                                        RegisterFileSimHelpers.conf.rfConf.tagWidth + RegisterFileSimHelpers.conf.htConf.hashValWidth - 1, 
                                                        RegisterFileSimHelpers.conf.htConf.hashValWidth))
        }

        dut.clockDomain.waitSampling(Random.nextInt(2)*50)

        val batchRefCountList = List.tabulate(execReshuffleBatchSize){ idx => {
          val randVal = Random.nextInt(1<<20)
          goldenRefCountList.append(randVal)
          randVal
        }}
        val batchSSDLBAList = List.tabulate(execReshuffleBatchSize){ idx => {
          val randVal = Random.nextInt(1<<20)
          goldenSSDLBAList.append(randVal)
          randVal
        }}
        val batchNodeIdxList = List.tabulate(execReshuffleBatchSize){ idx => {
          val randVal = Random.nextInt(nodeCount)
          goldenNodeIdxList.append(randVal)
          randVal
        }}

        // shuffle and write back mock res
        val shuffleMap = Random.shuffle(List.tabulate(execReshuffleBatchSize){idx => idx})
        val wbWaitCycles = Random.nextInt(2)*10
        for (instrIdx <- 0 until execReshuffleBatchSize){
          dut.clockDomain.waitSampling(wbWaitCycles)
          val remappedIdx = shuffleMap(instrIdx)
          val writeBackRes = RegisterFileSimHelpers.writeBackResGen(RefCount = batchRefCountList(remappedIdx), 
                                                                    SSDLBA   = batchSSDLBAList  (remappedIdx), 
                                                                    nodeIdx  = batchNodeIdxList (remappedIdx), 
                                                                    tag      = batchTagList     (remappedIdx))
          dut.io.lookupResWriteBackIn.sendData(dut.clockDomain, writeBackRes)
          
        }

        println(s"[DEBUG]: Finished execAndWriteBack ${batchIdx} out of ${(totalInstrCount/execReshuffleBatchSize)}")
      }
    }

    /* Res Watch*/
    val resWatch = fork {
      for (resIdx <- 0 until totalInstrCount) {
        finalResList.append(dut.io.lookupResOut.recvData(dut.clockDomain))
      }
      println("[DEBUG]: Done resWatch")
    }

    instrPush.join()
    execAndWriteBack.join()
    resWatch.join()
    
    assert(unregisteredInstrList.length == totalInstrCount)
    assert(finalResList.length == totalInstrCount)

    assert(goldenSHA3List    .length == totalInstrCount)
    assert(goldenRefCountList.length == totalInstrCount)
    assert(goldenSSDLBAList  .length == totalInstrCount)
    assert(goldenNodeIdxList .length == totalInstrCount)
    assert(goldenOpCodeList  .length == totalInstrCount)

    for (idx <- 0 until totalInstrCount){
      val res = finalResList(idx)
      val resSHA3Hash = SimHelpers.bigIntTruncVal(res, RegisterFileSimHelpers.conf.htConf.hashValWidth - 1, 0)
      val resRefCount = SimHelpers.bigIntTruncVal(res, 
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth + RegisterFileSimHelpers.conf.htConf.ptrWidth - 1,
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth)
      val resSSDLBA   = SimHelpers.bigIntTruncVal(res, 
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth + RegisterFileSimHelpers.conf.htConf.ptrWidth * 2 - 1, 
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth + RegisterFileSimHelpers.conf.htConf.ptrWidth)
      val resNodeIdx  = SimHelpers.bigIntTruncVal(res, 
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth + RegisterFileSimHelpers.conf.htConf.ptrWidth * 2 + RegisterFileSimHelpers.conf.rfConf.nodeIdxWidth - 1, 
                                                  RegisterFileSimHelpers.conf.htConf.hashValWidth + RegisterFileSimHelpers.conf.htConf.ptrWidth * 2)
      val resOpCode   = SimHelpers.bigIntTruncVal(res, 
                                                  HashTableLookupRes(RegisterFileSimHelpers.conf).getBitsWidth - 1, 
                                                  HashTableLookupRes(RegisterFileSimHelpers.conf).getBitsWidth - DedupCoreOp().getBitsWidth)
      
      assert(resSHA3Hash == goldenSHA3List    (idx))
      assert(resRefCount == goldenRefCountList(idx))
      assert(resSSDLBA   == goldenSSDLBAList  (idx))
      assert(resNodeIdx  == goldenNodeIdxList (idx))
      assert(resOpCode   == goldenOpCodeList  (idx))

      // println()
      // println(s"Instr: ${idx}")
      // println(s"resSHA3Hash: ${resSHA3Hash} == ${goldenSHA3List    (idx)}")
      // println(s"resRefCount: ${resRefCount} == ${goldenRefCountList(idx)}")
      // println(s"resSSDLBA:   ${resSSDLBA  } == ${goldenSSDLBAList  (idx)}")
      // println(s"resNodeIdx:  ${resNodeIdx } == ${goldenNodeIdxList (idx)}")
      // println(s"resOpCode:   ${resOpCode  } == ${goldenOpCodeList  (idx)}")
    }
  }
}