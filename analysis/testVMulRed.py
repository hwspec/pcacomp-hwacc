import cocotb
from cocotb.clock import Clock
from cocotb.triggers import Timer, RisingEdge, FallingEdge

from pcacomp import *


def strbin2sint(str):
    ival = int(str,2)
    if str[0] == "1":
        m = (1<<len(str)) - 1
        ival = -((m-ival)+1)
    return ival

@cocotb.test()
async def testVMulRed(dut):

    clock = Clock(dut.clock, 190, units="ns") # 10ns clock
    cocotb.start_soon(clock.start())
    
    dut.reset = 1
    await FallingEdge(dut.clock)
    dut.reset = 0
    await FallingEdge(dut.clock)


    basename='data1small'

    sprime = 25
    verbose = False
    nbits = 7  # nbits for quantized inv. enc. mat.

    datafn = f'data/{basename}.npy'
    encfn  = f'data/{basename}-encoding.npy'

    (dataframes, redenc, invenc, data_shape_orig) = loadfiles(sprime, datafn, encfn, verbose)

    w = data_shape_orig[1]
    h = data_shape_orig[2]

    qv = getquantizationvector(invenc, nbits)

    #
    d = dataframes[0]
    dataprec='int16'
    invprec='int32'
    redprec='float32'
    rem=redenc
    iem=invenc
    nsplits=16
    #
    
    data = np.array([d]).astype(dataprec)

    for s in range(0, sprime):
        iem[:,s] /= qv[s]

    invsprime = iem.astype(invprec)

    # element-wise matrix multiply
    invsprimeT = invsprime.T

    # input to the circuit
    if (len(data[0])%nsplits)>0:
        print(f'Error: illegal nsplits={nsplits}')
        sys.exit(0)
    splitlen=int(len(data[0])/nsplits)

    tmp_weighting_matrix = []
    for sp in range(0, sprime):
        sumsp = 0
        for si in range(0, nsplits):
            splitstart=si*splitlen
            splitend=(si+1)*splitlen # exclusive
            split_data = data[:, splitstart:splitend]
            split_iem = invsprimeT[sp, splitstart:splitend]
            
            ref_split_data = split_data.astype('int32')
            ref_split_iem = split_iem.astype('int32')
            ref_sumsp = split_data*split_iem
            ref_sumsp = np.sum(ref_sumsp)
            # DUT with split_data split_iem
            for i in range(0,splitlen):
                pxval = int(split_data[0, i])
                iemval = int(split_iem[i])

                setattr(dut, f"io_in_px_{i}",  pxval)
                setattr(dut, f"io_in_iem_{i}", iemval)

            await FallingEdge(dut.clock)
            v = strbin2sint(dut.io_out.value.binstr)
            if (v != ref_sumsp):
                print(f'Error!!: {sp}-{si}: out={v} ref={ref_sumsp}')
                sys.exit(1)

            await FallingEdge(dut.clock)
            
            sumsp += ref_sumsp
        tmp_weighting_matrix.append(float(sumsp))

    dut_weighting_matrix = np.array(tmp_weighting_matrix)
    
    #
    elemwise = (data * invsprimeT)
    weighting_matrix = elemwise.T #  data, invprec
    #print(f'min max {np.min(weighting_matrix)} {np.max(weighting_matrix)}')
    weighting_matrix = weighting_matrix.astype(redprec)
    # reduction in higher precision
    weighting_matrix = np.sum(weighting_matrix, axis=0)

    for s in range(0, sprime):
        weighting_matrix[s] *= qv[s]
        dut_weighting_matrix[s] *= qv[s]

    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)
    dut_data_approx = np.matmul(dut_weighting_matrix, rem, dtype=np.float64)
    dut_data_approx = np.clip(dut_data_approx, 0, np.inf)

    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    dut_mse = np.sum((data - dut_data_approx)**2) / (data.shape[1])
    print(mse, dut_mse)

    
        
    pass


