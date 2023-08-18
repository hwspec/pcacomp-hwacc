import cocotb
from cocotb.clock import Clock
from cocotb.triggers import Timer, RisingEdge, FallingEdge

from evaluate_pca_comp import basic_stats

@cocotb.test()
async def testVMulRed(dut):

    clock = Clock(dut.clock, 190, units="ns") # 10ns clock
    cocotb.start_soon(clock.start())
    
    dut.reset = 1
    await FallingEdge(dut.clock)
    dut.reset = 0
    await FallingEdge(dut.clock)

    for i in range(0,256):
        setattr(dut, f"io_in_px_{i}", 1)
        setattr(dut, f"io_in_iem_{i}", 1)

    await FallingEdge(dut.clock)
    
    print(f'out={dut.io_out.value}')

    await FallingEdge(dut.clock)
        
    pass


