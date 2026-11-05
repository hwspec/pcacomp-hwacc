import cocotb

from axipca_bridge import AxiPCACompBlockBridge


@cocotb.test()
async def tb_base(cocotb_dut):
    dut = AxiPCACompBlockBridge(cocotb_dut)
    await dut.setup()
    await dut.softReset()

    dut.log.info("Verified!")
