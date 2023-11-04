package util

import spinal.core._
import spinal.lib._

case class CntIncDec(bitCnt: BitCount, incFlag: Bool, decFlag: Bool) {

  val cnt = Reg(UInt(bitCnt)).init(0)
  val willOverflow = (cnt === (1<<bitCnt.value) -1) && incFlag
  val willOverflowIfInc = (cnt === (1<<bitCnt.value) -1)
  val willUnderflow = (cnt === 0) && decFlag
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch(incFlag ## decFlag) {
    is(True ## False) (cnt := cnt + 1)
    is(False ## True) (cnt := cnt -1)
  }

  when(willClear) (cnt.clearAll())

}

case class CntDynmicBound(upBoundEx: UInt, incFlag: Bool, decFlag: Bool = False) {

  val cnt = Reg(UInt(upBoundEx.getWidth bits)).init(0)
  val willOverflowIfInc = (cnt === upBoundEx -1)
  val willUnderflowIfDec = (cnt === 0)
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch(incFlag ## decFlag) {
    is(True ## False) (cnt := cnt + 1)
    is(False ## True) (cnt := cnt -1)
  }

  when(willClear || (willOverflowIfInc && incFlag)) (cnt.clearAll())

}

case class AccumIncDec(bitCnt: BitCount, incFlag: Bool, decFlag: Bool, incVal: UInt, decVal: UInt) {

  val accum = Reg(UInt(bitCnt)).init(0)
  val willClear = False.allowOverride

  def clearAll(): Unit = willClear := True

  switch(incFlag ## decFlag) {
    is(True ## True) (accum := accum + incVal - decVal)
    is(True ## False) (accum := accum + incVal)
    is(False ## True) (accum := accum - decVal)
  }

  when(willClear) (accum.clearAll())
}


case class pauseTimeOut(bitCnt: BitCount, inc: Bool, rst: Bool, pause: Bool = False) {

  val cnt = Counter(bitCnt)
  val rPause = RegNextWhen(True, pause)

  when(inc && ~rPause && ~cnt.willOverflowIfInc) (cnt.increment())
  when(rst) (this.clear())

  def clear(): Unit = {
    cnt.clear()
    rPause.clear()
  }

  // hold til rst
  def isTimeOut: Bool = cnt.willOverflowIfInc

}


