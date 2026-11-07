import random

import cocotb

from axipca_bridge import AxiPCACompBlockBridge

# Mirrors the register sequencing in AxiPCACompBlockSpec.scala:
# loadIEM -> verifyIEM -> commit each row -> start_feed/drain -> check outq.
# Test data is generated and scored here instead of reusing Scala's seeded
# PCATestData, since the DUT has no baked-in reference: both the IEM matrix
# and the input rows are supplied over AXI, so any self-consistent data works.


def _log2ceil(x: int) -> int:
    return (x - 1).bit_length() if x > 1 else 0


def _pack(values, bw):
    mask = (1 << bw) - 1
    v = 0
    for i, val in enumerate(values):
        v |= (val & mask) << (i * bw)
    return v


def _unpack_signed(bits, bw, n):
    mask = (1 << bw) - 1
    sbit = 1 << (bw - 1)
    out = []
    for i in range(n):
        masked = (bits >> (i * bw)) & mask
        out.append(masked - (1 << bw) if masked & sbit else masked)
    return out


async def load_iem(dut, h, m, encbw, iemrows):
    for rowid in range(h):
        for encid in range(m):
            await dut.writeWord(dut.p.iem_rowid_w, rowid)
            await dut.writeWord(dut.p.iem_pos_w, encid)
            await dut.writeWord(dut.p.iem_data_w, _pack(iemrows[encid][rowid], encbw))


async def verify_iem(dut, h, m, encbw, iemrows):
    await dut.writeWord(dut.p.iem_verifymode_w, 1)
    for rowid in range(h):
        for encid in range(m):
            await dut.writeWord(dut.p.iem_rowid_w, rowid)
            await dut.writeWord(dut.p.iem_pos_w, encid)
            ref = _pack(iemrows[encid][rowid], encbw)
            await dut.expectWord(dut.p.iem_verify_r, ref,
                                  msg=f"failed to verify IEM: rowid={rowid} encid={encid}")
    await dut.writeWord(dut.p.iem_verifymode_w, 0)


async def commit_row(dut, rowid, pixrow, pxbw, nwordsperrow):
    packed = _pack(pixrow, pxbw)
    for wordoffset in range(nwordsperrow):
        word = (packed >> (wordoffset * 32)) & 0xFFFFFFFF
        await dut.writeWord(dut.p.fillup_w + 4 * wordoffset, word)
    await dut.writeWord(dut.p.commit_w, rowid)


async def start_feed_and_drain(dut, maxloopcnt=10000):
    await dut.writeWord(dut.p.start_feed_w, 1)
    drained = 0
    tries = 0
    while drained == 0:
        drained = await dut.readWord(dut.p.drained_r)
        tries += 1
        assert tries < maxloopcnt, "AxiPCACompBlock did not drain in time"


@cocotb.test()
async def tb_base(cocotb_dut):
    dut = AxiPCACompBlockBridge(cocotb_dut)
    await dut.setup()

    await dut.expectWord(dut.p.const1_r, dut.p.const1)
    await dut.expectWord(dut.p.const2_r, dut.p.const2)

    h, m, nblocks = dut.p.pca_h, dut.p.pca_m, dut.p.pca_nblocks
    pxbw, encbw = dut.p.pca_pxbw, dut.p.pca_encbw
    width = dut.p.pca_w // nblocks

    nwordsperrow = (width * pxbw + 31) // 32
    redbw = pxbw + encbw + _log2ceil(width) + _log2ceil(h)

    rnd = random.Random(0xC0FFEE)
    pixrows = [[rnd.randrange(0, 1 << pxbw) for _ in range(width)] for _ in range(h)]
    iemrows = [[[rnd.randrange(-(1 << (encbw - 1)), 1 << (encbw - 1)) for _ in range(width)]
                for _ in range(h)] for _ in range(m)]

    expected = [sum(iemrows[encid][rowid][x] * pixrows[rowid][x]
                     for rowid in range(h) for x in range(width))
                for encid in range(m)]

    await load_iem(dut, h, m, encbw, iemrows)
    await verify_iem(dut, h, m, encbw, iemrows)

    for rowid in range(h):
        await commit_row(dut, rowid, pixrows[rowid], pxbw, nwordsperrow)
    await dut.expectWord(dut.p.inq_cnt_r, h)

    await start_feed_and_drain(dut)

    await dut.expectWord(dut.p.outq_cnt_r, 1)
    res = await dut.readWord(dut.p.outq_r)
    result = _unpack_signed(res, redbw, m)
    await dut.expectWord(dut.p.outq_cnt_r, 0)

    assert result == expected, f"dut={result} ref={expected}"

    await dut.softReset()
    await dut.expectWord(dut.p.drained_r, 0)

    dut.log.info("Verified!")
