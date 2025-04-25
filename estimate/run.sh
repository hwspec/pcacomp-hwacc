#VF=`ls -1 ../generated/BaseLine*.sv`
#VF='../generated/BaseLinePCAComp_16x16_bw10_iembw8_sz50.sv'
VF="
../generated/BaseLinePCAComp_4x4_bw9_iembw8_sz1.sv
../generated/BaseLinePCAComp_8x8_bw9_iembw8_sz1.sv
../generated/BaseLinePCAComp_32x32_bw9_iembw8_sz1.sv
"


for fn in $VF; do
	echo $f
    BN=`basename $fn`
    TOP=${BN%%.sv}
#	time ./yosys-fpga-techmap-stat.sh  $fn | tee -a stat_lut_$TOP.txt
	time ./yosys-techmap-stat.sh $fn | tee -a stat_cells_$TOP.txt
done
