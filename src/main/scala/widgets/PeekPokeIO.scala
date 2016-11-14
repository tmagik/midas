package midas_widgets

import Chisel._
import cde.Parameters

class PeekPokeIOWidgetIO(inNum: Int, outNum: Int)(implicit p: Parameters)
    extends WidgetIO()(p) {
  // Channel width == width of simulation MMIO bus
  val ins  = Vec(inNum, Decoupled(UInt(width = ctrl.nastiXDataBits)))
  val outs = Flipped(Vec(outNum, Decoupled(UInt(width = ctrl.nastiXDataBits))))

  val step = Flipped(Decoupled(UInt(width = ctrl.nastiXDataBits)))
  val idle = Bool(OUTPUT)
  val tReset = Bool(OUTPUT)
}

// The interface to this widget is temporary, and matches the Vec of channels
// the sim wrapper produces. Ultimately, the wrapper should have more coarsely
// tokenized IOs.
class PeekPokeIOWidget(inputs: Seq[(String, Int)], outputs: Seq[(String, Int)])
    (implicit p: Parameters) extends Widget()(p) {
  val numInputChannels = inputs.unzip._2.reduce(_ + _)
  val numOutputChannels = outputs.unzip._2.reduce(_ + _)
  val io = IO(new PeekPokeIOWidgetIO(numInputChannels, numOutputChannels))

  // i = input, o = output tokens (as seen from the target)
  val iTokensAvailable = RegInit(UInt(0, width = io.ctrl.nastiXDataBits))
  val oTokensPending = RegInit(UInt(1, width = io.ctrl.nastiXDataBits))

  io.idle := iTokensAvailable === UInt(0) && oTokensPending === UInt(0)

  // A higher order funciton that takes a hardware elaborator for each channel.
  // Based on the number of chunks in each signal binds the appropriate
  // number of programmable registers to them
  def bindChannels(bindSignal: (String, Int) => Int)(
      signals: Seq[(String, Int)], offset: Int): Seq[Int] = signals match {
    case Nil => Nil
    case (name: String, width: Int) :: sigs => {
      val address = if (width == 1) {
        bindSignal(name, offset)
      } else {
        // Need to append an offset to the name for each chunk
         (0 to width).toSeq.map({chunk => bindSignal(s"${name}_$chunk", offset + chunk)}).head
      }
      // Bind the next signal; moving further down 
      address +: bindChannels(bindSignal)(sigs, offset + width)
    }
  }

  def bindInputs = bindChannels( (name, offset) => {
    val channel = io.ins(offset)
    val reg = Reg(channel.bits)
    reg suggestName ("target_" + name)
    channel.bits := reg
    channel.valid := iTokensAvailable =/= UInt(0)
    attach(reg, name)
  }) _

  def bindOutputs = bindChannels((name, offset) => {
    val channel = io.outs(offset)
    val reg = RegEnable(channel.bits, channel.fire)
    reg suggestName ("target_" + name)
    channel.ready := oTokensPending =/= UInt(0)
    attach(reg, name)
  }) _

  val inputAddrs = bindInputs(inputs, 0)
  val outputAddrs = bindOutputs(outputs, 0)

  val fromHostReady = io.ins.foldLeft(Bool(true))(_ && _.ready)
  val toHostValid = io.outs.foldLeft(Bool(true))(_ && _.valid)

  when (iTokensAvailable =/= UInt(0) && fromHostReady) {
    iTokensAvailable := iTokensAvailable - UInt(1)
  }
  when (oTokensPending =/= UInt(0) && toHostValid) {
    oTokensPending := oTokensPending - UInt(1)
  }

  when (io.step.fire) {
    iTokensAvailable := io.step.bits
    oTokensPending := io.step.bits
  }
  // For now do now, do not allow the block to be stepped further, unless
  // it has gone idle
  io.step.ready := io.idle

  io.tReset := io.ins(0).bits

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder): Unit = {
    import CppGenerationUtils._
    // offsets

    def genOffsets(signals: Seq[String]): Unit = (signals.zipWithIndex) foreach {
      case (name, idx) => sb.append(genConstStatic(name, UInt32(idx)))}

    sb.append(genComment("Pokeable target inputs"))
    sb.append(genMacro("POKE_SIZE", UInt64(inputs.size)))
    genOffsets(inputs.unzip._1)
    sb.append(genArray("INPUT_ADDRS", inputAddrs map(off => UInt32(base + off))))
    sb.append(genArray("INPUT_NAMES", inputs.unzip._1.map(CStrLit(_))))
    sb.append(genArray("INPUT_CHUNKS", inputs.unzip._2.map(UInt32(_))))

    sb.append(genComment("Peekable target outputs"))
    sb.append(genMacro("PEEK_SIZE", UInt64(outputs.size)))
    genOffsets(outputs.unzip._1)
    sb.append(genArray("OUTPUT_ADDRS", outputAddrs map(off => UInt32(base + off))))
    sb.append(genArray("OUTPUT_NAMES", outputs.unzip._1.map(CStrLit(_))))
    sb.append(genArray("OUTPUT_CHUNKS", outputs.unzip._2.map(UInt32(_))))
  }
}
