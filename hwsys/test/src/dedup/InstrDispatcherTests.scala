// package dedup

// import org.scalatest.funsuite.AnyFunSuite
// import spinal.core.sim._
// import util.sim._
// import util.sim.SimDriver._

// import scala.collection.mutable
// import scala.collection.mutable._
// import scala.util.Random

// import spinal.core._
// import spinal.lib._

// class InstrDispatcherTests extends AnyFunSuite {
//   def instrDispatcherSim(): Unit = {

//     val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new InstrDispatcherTB(DedupConfig()))
//     else SimConfig.withWave.compile(new InstrDispatcherTB(DedupConfig()))

//     compiledRTL.doSim { dut =>
//       InstrDispatcherSim.doSim(dut)
//     }
//   }

//   test("InstrDispatcherTest"){
//     instrDispatcherSim()
//   }
// }


// object InstrDispatcherSim {

//   def doSim(dut: InstrDispatcherTB, verbose: Boolean = false): Unit = {
//     dut.clockDomain.forkStimulus(period = 2)
//     SimTimeout(1000000)
//     dut.io.rawInstrStream.valid #= false
//     dut.io.writeInstrStream.ready #= false
//     dut.io.eraseInstrStream.ready #= false
//     dut.io.readInstrStream.ready #= false
//     dut.io.flush #= false
//     dut.clockDomain.waitSampling(10)

//     /** init */
//     dut.io.flush #= true
//     dut.clockDomain.waitSampling()
//     dut.io.flush #= false
//     dut.clockDomain.waitSampling()

//     /** generate instr stream */
//     val instrNum = 256
//     val numInstrQueue = 3
//     val totalInstr = instrNum * numInstrQueue
//     val instrSimHelper = new rawInstrHelper(DedupConfig())
    
//     // [[www][eee][rrr]]
//     var instrStrmData: ListBuffer[ListBuffer[BigInt]] = ListBuffer()
//     // [0,1,0]
//     var pointerList: ListBuffer[Int] = ListBuffer()
//     // generate 3 buffers
//     for (i <- 0 until numInstrQueue) {
//       val oneInstrQueue: ListBuffer[BigInt] = ListBuffer()
//       pointerList += 0
//       for (j <- 0 until instrNum) {
//         oneInstrQueue += instrSimHelper.randInstrGen(i, false)
//         // println(s" ($i ,$j)")
//         // oneInstrQueue.zipWithIndex.map{ case (e,i) =>
//         //   println(SimHelpers.bigIntTruncVal(e, 511, 500))
//         // }
//       }
//       instrStrmData += oneInstrQueue
//     }

//     var mergedInstrData: ListBuffer[BigInt] = ListBuffer()
//     for (i <- 0 until totalInstr) {
//       var instrQNum = Random.nextInt(numInstrQueue)
//       while (pointerList(instrQNum) >= instrNum){
//         // Q is full
//         instrQNum = (instrQNum + 1) % numInstrQueue
//       }
//       // println(instrStrmData(instrQNum)(pointerList(instrQNum)).toString(2))
//       mergedInstrData.append(instrStrmData(instrQNum)(pointerList(instrQNum)))
//       // point to next
//       pointerList(instrQNum) = pointerList(instrQNum) + 1
//     }

//     /* Stimuli injection */
//     val instrStrmPush = fork {
//       var opIdx: Int = 0
//       for (opIdx <- 0 until totalInstr) {
//         dut.clockDomain.waitSampling(Random.nextInt(10))
//         dut.io.rawInstrStream.sendData(dut.clockDomain, mergedInstrData(opIdx))
//         // println(SimHelpers.bigIntTruncVal(mergedInstrData(opIdx), 511, 510))
//         // println(s"[INSTR PUSH] opIdx = $opIdx")
//       }
//     }
    
//     /* output watcher */
//     val writeInstrWatch = fork {
//       val outputWriteInstr: ListBuffer[BigInt] = ListBuffer()
//       for (writeInstrIdx <- 0 until instrNum) {
//         dut.clockDomain.waitSampling(Random.nextInt(100))
//         val respData = dut.io.writeInstrStream.recvData(dut.clockDomain)
//         // println(respData.toString(16))
//         val rawInstr = instrSimHelper.writeInstrFromRes(respData, false)
//         outputWriteInstr += rawInstr
//         // println(s"[WRITE RECV] writeInstrIdx = $writeInstrIdx")
//       }
//       (outputWriteInstr, instrStrmData(0)).zipped.map{ case (output, expected) =>
//         assert(output == expected)
//       }
//     }

//     val eraseInstrWatch = fork {
//       val outputEraseInstr: ListBuffer[BigInt] = ListBuffer()
//       for (eraseInstrIdx <- 0 until instrNum) {
//         dut.clockDomain.waitSampling(Random.nextInt(100))
//         val respData = dut.io.eraseInstrStream.recvData(dut.clockDomain)
//         val rawInstr = instrSimHelper.eraseInstrFromRes(respData, false)
//         outputEraseInstr += rawInstr
//         // println(s"[ERASE RECV] eraseInstrIdx = $eraseInstrIdx")
//       }
//       (outputEraseInstr, instrStrmData(1)).zipped.map{ case (output, expected) =>
//         assert(output == expected)
//       }
//     }

//     val readInstrWatch = fork {
//       val outputReadInstr: ListBuffer[BigInt] = ListBuffer()
//       for (readInstrIdx <- 0 until instrNum) {
//         dut.clockDomain.waitSampling(Random.nextInt(100))
//         val respData = dut.io.readInstrStream.recvData(dut.clockDomain)
//         val rawInstr = instrSimHelper.readInstrFromRes(respData, false)
//         outputReadInstr += rawInstr
//         // println(s"[READ RECV] readInstrIdx = $readInstrIdx")
//       }
//       (outputReadInstr, instrStrmData(2)).zipped.map{ case (output, expected) =>
//         assert(output == expected)
//       }
//     }

//     // wait until finish
//     instrStrmPush.join()
//     writeInstrWatch.join()
//     eraseInstrWatch.join()
//     readInstrWatch.join()

//   }
// }

// class rawInstrHelper(conf: DedupConfig){
//   val instrBitWidth = DedupCoreOp().getBitsWidth

//   def randInstrGen(instrIdx : Int, printRes : Boolean = false) : BigInt = instrIdx match{
//     case 0 => writeInstrGen(1 + Random.nextInt().abs, 1 + Random.nextInt().abs, printRes)
//     case 1 => eraseInstrGen(1 + Random.nextInt().abs, 1 + Random.nextInt().abs, printRes)
//     case 2 => readInstrGen (1 + Random.nextInt().abs, 1 + Random.nextInt().abs, printRes)
//     case _ => readInstrGen (1 + Random.nextInt().abs, 1 + Random.nextInt().abs, printRes)
//   }
  
//   // generate 512 bit representation of instruction
//   def writeInstrGen(start:BigInt, len:BigInt, printRes : Boolean = false) : BigInt = {
//     val start_trunc = SimHelpers.bigIntTruncVal(start, conf.LBAWidth - 1, 0)
//     val len_trunc   = SimHelpers.bigIntTruncVal(len  , conf.LBAWidth - 1, 0)
//     if (printRes){
//       println(s"[WRITE] opcode = 0, start=$start_trunc, len=$len_trunc")
//     }
//     var rawInstr = BigInt(0)
//     rawInstr = rawInstr + (BigInt(0) << (conf.instrTotalWidth - instrBitWidth))
//     rawInstr = rawInstr + (start_trunc << conf.LBAWidth)
//     rawInstr = rawInstr + (len_trunc << 0)
//     rawInstr
//   }

//   def eraseInstrGen(crc:BigInt, sha3:BigInt, printRes : Boolean = false) : BigInt = {
//     val crc_trunc  = SimHelpers.bigIntTruncVal(crc , 255 + 96 - 1, 255)
//     val sha3_trunc = SimHelpers.bigIntTruncVal(sha3, 255, 0)
//     if (printRes){
//       println(s"[ERASE] opcode = 1, crc=$crc, sha3=$sha3")
//     }
//     var rawInstr = BigInt(0)
//     rawInstr = rawInstr + (BigInt(1) << (conf.instrTotalWidth - instrBitWidth))
//     rawInstr = rawInstr + (crc << 256)
//     rawInstr = rawInstr + (sha3 << 0)
//     rawInstr
//   }

//   def readInstrGen(start:BigInt, len:BigInt, printRes : Boolean = false) : BigInt = {
//     val start_trunc = SimHelpers.bigIntTruncVal(start, conf.LBAWidth - 1, 0)
//     val len_trunc   = SimHelpers.bigIntTruncVal(len  , conf.LBAWidth - 1, 0)
//     if (printRes){
//       println(s"[READ] opcode = 2, start=$start_trunc, len=$len_trunc")
//     }
//     var rawInstr = BigInt(0)
//     rawInstr = rawInstr + (BigInt(2) << (conf.instrTotalWidth - instrBitWidth))
//     rawInstr = rawInstr + (start_trunc << conf.LBAWidth)
//     rawInstr = rawInstr + (len_trunc << 0)
//     rawInstr
//   }

//   // decodeinstruction from output results
//   def writeInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
//     val opcode = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 + instrBitWidth - 1, conf.LBAWidth * 2)
//     val start = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 - 1, conf.LBAWidth)
//     val len = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth-1, 0)
//     assert(opcode.toInt == 0)
//     // println(s"[WRITE RESP] opcode = $opcode")
//     writeInstrGen(start, len, printRes)
//   }

//   def eraseInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
//     val opcode = SimHelpers.bigIntTruncVal(respData, 256 + 96 + instrBitWidth - 1, 256 + 96)
//     val crc = SimHelpers.bigIntTruncVal(respData, 256 + 96 - 1, 256)
//     val sha3 = SimHelpers.bigIntTruncVal(respData, 255, 0)
//     assert(opcode.toInt == 1)
//     // println(s"[ERASE RESP] opcode = $opcode")
//     eraseInstrGen(crc, sha3, printRes)
//   }

//   def readInstrFromRes(respData:BigInt, printRes : Boolean = false) : BigInt = {
//     val opcode = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 + instrBitWidth - 1, conf.LBAWidth * 2)
//     val start = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth * 2 - 1, conf.LBAWidth)
//     val len = SimHelpers.bigIntTruncVal(respData, conf.LBAWidth - 1, 0)
//     assert(opcode.toInt == 2)
//     // println(s"[READ RESP] opcode = $opcode")
//     readInstrGen(start, len, printRes)
//   }

// }

// /* We are using toBigInt() in receiving output data.
//   But this doesn't work with crc in Bundle{Vec[Bits]}
//   add asBits in TB to translate: Vec -> Bits
// */
// case class ERASEREFInstrTB (conf: DedupConfig) extends Bundle {
//   val SHA3Hash = Bits(256 bits)
//   val CRCHash = Bits(96 bits)
//   val opCode = DedupCoreOp()
// }

// case class InstrDispatcherTB(conf: DedupConfig) extends Component{

//   val instrQueueNum = conf.instrQueueLogDepth.length
//   assert(instrQueueNum == 3)
//   val instrBitWidth = DedupCoreOp().getBitsWidth

//   val io = new Bundle {
//     /* input raw Instr Stream: 512bits*/
//     val rawInstrStream = slave Stream (Bits(conf.instrTotalWidth bits))

//     /* output instr stream */
//     // val instrIssueStream: Vec[Stream[Bundle with DedupCoreInstr]] = out Vec(Stream (WRITE2FREEInstr(conf)), Stream (ERASEREFInstr(conf)), Stream (READSSDInstr(conf)))
//     val writeInstrStream = master Stream (WRITE2FREEInstr(conf))
//     val eraseInstrStream = master Stream (ERASEREFInstrTB(conf))
//     val readInstrStream  = master Stream (READSSDInstr(conf))
    
//     /* flush */
//     val flush = in Bool() default(False)

//     /* instr queue states */
//     val occupancy    = out Vec(UInt(conf.instrQueueLogDepth(0) + 1 bits), UInt(conf.instrQueueLogDepth(1) + 1 bits), UInt(conf.instrQueueLogDepth(2) + 1 bits))
//     val availability = out Vec(UInt(conf.instrQueueLogDepth(0) + 1 bits), UInt(conf.instrQueueLogDepth(1) + 1 bits), UInt(conf.instrQueueLogDepth(2) + 1 bits))
//   }
  
//   val instrDispatcher = new InstrDispatcher(DedupConfig())
//   io.rawInstrStream >> instrDispatcher.io.rawInstrStream
//   io.writeInstrStream << instrDispatcher.io.writeInstrStream
//   io.eraseInstrStream.translateFrom(instrDispatcher.io.eraseInstrStream){ (a, b) =>
//     a.SHA3Hash := b.SHA3Hash
//     a.CRCHash  := b.CRCHash.asBits
//     a.opCode   := b.opCode
//   }
//   io.readInstrStream << instrDispatcher.io.readInstrStream

//   instrDispatcher.io.flush := io.flush
//   io.occupancy             := instrDispatcher.io.occupancy
//   io.availability          := instrDispatcher.io.availability
// }